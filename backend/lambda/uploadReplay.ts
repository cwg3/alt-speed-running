import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { gzipSync } from 'zlib';
import { resolveSessionToken } from './lib/auth';
import { MatchPlayer } from './lib/matchCompletion';
import { checkReplay, Sample } from './lib/replayChecks';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const s3 = new S3Client({});

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const REPLAY_BUCKET = process.env.REPLAY_BUCKET!;

// One sample per second, so this is about two hours of play. Beyond it
// the client is misbehaving rather than playing.
// Two hours at the recorder's 10Hz. This moved with the sample rate;
// left at 7200 the backend would have rejected every run past twelve
// minutes, which is to say every run worth reviewing.
const MAX_SAMPLES = 72000;

interface ReplayRequest {
	matchId: string;
	samples: Sample[];
}

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const uploaderUuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uploaderUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	let body: ReplayRequest;
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}
	if (!body.matchId || !Array.isArray(body.samples)) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId and samples are required' }) };
	}
	if (body.samples.length === 0 || body.samples.length > MAX_SAMPLES) {
		return {
			statusCode: 400,
			body: JSON.stringify({ error: `samples must be between 1 and ${MAX_SAMPLES} entries` }),
		};
	}
	// 5 = the original [t, dim, x, y, z]. 7 adds [yaw, pitch].
	//
	// Both are accepted because the client version gate enforces a
	// MINIMUM, not an exact build: a client on the current minimum
	// still sends five-wide rows. Rejecting those would turn an
	// optional upload into a hard failure at the end of somebody's
	// match.
	if (!body.samples.every((s) => Array.isArray(s)
			&& (s.length === 5 || s.length === 7) && s.every(Number.isFinite))) {
		return {
			statusCode: 400,
			body: JSON.stringify({ error: 'each sample must be [elapsedMs, dimension, x, y, z]' }),
		};
	}

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}
	const players: MatchPlayer[] = match.Item.players;
	if (!players.some((p) => p.uuid === uploaderUuid)) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}

	// Timelines compress extremely well - they're mostly small
	// monotonic deltas - so gzip before storing rather than paying for
	// raw JSON.
	const key = `${body.matchId}/${uploaderUuid}.json.gz`;
	await s3.send(new PutObjectCommand({
		Bucket: REPLAY_BUCKET,
		Key: key,
		Body: gzipSync(JSON.stringify(body.samples)),
		ContentType: 'application/json',
		ContentEncoding: 'gzip',
	}));

	const splits: Record<string, number> = (match.Item.splits ?? {})[uploaderUuid] ?? {};
	const result = checkReplay(body.samples, splits);

	// Findings go on the match beside the statistical review so a
	// reviewer sees one picture rather than having to correlate
	// sources. The parent map has to exist before a nested path can be
	// assigned, hence the two steps.
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET replays = if_not_exists(replays, :empty)',
		ExpressionAttributeValues: { ':empty': {} },
	}));

	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET replays.#uuid = :entry, needsReview = :needs',
		ExpressionAttributeNames: { '#uuid': uploaderUuid },
		ExpressionAttributeValues: {
			':entry': {
				key,
				sampleCount: result.sampleCount,
				durationMs: result.durationMs,
				findings: result.findings,
				uploadedAt: Date.now(),
			},
			':needs': result.findings.length > 0 || match.Item.needsReview === true,
		},
	}));

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			stored: true,
			sampleCount: result.sampleCount,
			findings: result.findings,
		}),
	};
};
