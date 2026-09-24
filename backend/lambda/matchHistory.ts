// A player's own match history, newest first.
//
//   GET /players/me/matches?limit=25&before=<completedAt>
//
// Reads only the caller's rows. The history table is keyed by uuid, so
// this cannot return anybody else's matches even by accident - there is
// no filter to get wrong.
//
// Each row is self-contained: opponent, result, rating change, seed
// type, and the world-setup version a replay would need. A history
// screen renders straight from the response without a second call per
// row.
import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const HISTORY_TABLE_NAME = process.env.MATCH_HISTORY_TABLE_NAME!;
const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;

/** Enough to fill a screen and scroll a little; paging handles the rest. */
const DEFAULT_LIMIT = 25;
const MAX_LIMIT = 100;

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const callerUuid = await resolveSessionToken(
		SESSIONS_TABLE_NAME, event.headers?.authorization ?? event.headers?.Authorization);
	if (!callerUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	const q = event.queryStringParameters ?? {};
	const limit = Math.min(MAX_LIMIT, Math.max(1, parseInt(q.limit ?? '', 10) || DEFAULT_LIMIT));
	// Paging by the sort key itself rather than an opaque cursor: the
	// caller already has the oldest completedAt it has seen.
	const before = parseInt(q.before ?? '', 10);

	const res = await ddb.send(new QueryCommand({
		TableName: HISTORY_TABLE_NAME,
		KeyConditionExpression: Number.isFinite(before)
			? '#u = :uuid AND completedAt < :before'
			: '#u = :uuid',
		ExpressionAttributeNames: { '#u': 'uuid' },
		ExpressionAttributeValues: Number.isFinite(before)
			? { ':uuid': callerUuid, ':before': before }
			: { ':uuid': callerUuid },
		ScanIndexForward: false,   // newest first
		Limit: limit,
	}));

	const matches = (res.Items ?? []).map((r) => ({
		matchId: r.matchId,
		completedAt: r.completedAt,
		opponentName: r.opponentName,
		opponentUuid: r.opponentUuid,
		won: r.won,
		ratingDelta: r.ratingDelta,
		seasonPointsAwarded: r.seasonPointsAwarded,
		seedType: r.seedType,
		overworldSeed: r.overworldSeed,
		netherSeed: r.netherSeed,
		worldSetupVersion: r.worldSetupVersion,
	}));

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matches,
			// Absent when there is nothing older, so a client knows to
			// stop rather than asking again and getting an empty page.
			nextBefore: matches.length === limit
				? matches[matches.length - 1].completedAt
				: undefined,
		}),
	};
};
