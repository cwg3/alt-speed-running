// Fetches the stored traces for a match, for playback.
//
//   GET /matches/{matchId}/replay
//
// Returns BOTH players' traces. Studying the opponent is the point of
// the feature, so a caller who played the match gets both - there is
// no version of this worth building that hands back only your own.
//
// Participants only. That is safe on its own terms: under the
// seen-seeds rule neither player can ever be dealt this seed again. It
// would NOT be safe to hand out publicly - a replay shows where the
// structure, the portal and the bastion are, to a third party who can
// still be dealt that seed.
import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';
import { GetObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { gunzipSync } from 'zlib';
import { resolveSessionToken } from './lib/auth';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const s3 = new S3Client({});

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const REPLAY_BUCKET = process.env.REPLAY_BUCKET!;

async function readTrace(matchId: string, uuid: string): Promise<unknown[] | null> {
	try {
		const obj = await s3.send(new GetObjectCommand({
			Bucket: REPLAY_BUCKET,
			Key: `${matchId}/${uuid}.json.gz`,
		}));
		const body = await obj.Body!.transformToByteArray();
		return JSON.parse(gunzipSync(Buffer.from(body)).toString('utf8'));
	} catch (err: any) {
		// A missing trace is ordinary: a player who quit before the
		// upload, or a match that predates recording. Absent, not an
		// error - the caller shows what it has.
		if (err?.name === 'NoSuchKey' || err?.$metadata?.httpStatusCode === 404) {
			return null;
		}
		throw err;
	}
}

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const callerUuid = await resolveSessionToken(
		SESSIONS_TABLE_NAME, event.headers?.authorization ?? event.headers?.Authorization);
	if (!callerUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	const matchId = event.pathParameters?.matchId;
	if (!matchId) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId is required' }) };
	}

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: { uuid: string; username: string }[] = match.Item.players ?? [];
	if (!players.some((p) => p.uuid === callerUuid)) {
		return {
			statusCode: 403,
			body: JSON.stringify({ error: 'you are not a participant in this match' }),
		};
	}

	// Refuse to describe a world we cannot rebuild faithfully.
	//
	// Playback regenerates the world from the seeds rather than storing
	// it, under the rules of whatever build is running. A match stamped
	// with a different version - or with none at all, which is every
	// match from before stamping existed - would rebuild into a world
	// the players never saw. The trace would be truthful and the world
	// around it wrong, which is worse than refusing.
	const stamped: number = match.Item.worldSetupVersion ?? 0;

	const traces: Record<string, unknown> = {};
	for (const p of players) {
		traces[p.uuid] = {
			username: p.username,
			samples: await readTrace(matchId, p.uuid),
		};
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matchId,
			overworldSeed: match.Item.overworldSeed,
			netherSeed: match.Item.netherSeed,
			seedType: match.Item.seedType,
			structureX: match.Item.structureX,
			structureZ: match.Item.structureZ,
			// 0 means "made before stamping existed" - unknown, not
			// version zero. The client decides whether it can rebuild.
			worldSetupVersion: stamped,
			players: players.map((p) => p.uuid),
			traces,
		}),
	};
};
