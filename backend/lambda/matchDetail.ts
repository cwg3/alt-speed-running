// One match, in enough detail to see where it was decided.
//
//   GET /matches/{matchId}/detail
//
// Both players' splits side by side. The comparison is the point: a
// list of your own times says how the run went, but the DELTA against
// the opponent says where the match was lost, which is the question
// anybody actually opens this to answer.
//
// Participants only. Same reasoning as the replay: under the
// seen-seeds rule neither player can draw this seed again, but a third
// party still can, and split times plus a seed type say more about a
// route than they look like they do.
import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { SPLIT_ORDER } from './lib/splitRules';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

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

	const res = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
	}));
	if (!res.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const m = res.Item;
	const players: { uuid: string; username: string }[] = m.players ?? [];
	if (!players.some((p) => p.uuid === callerUuid)) {
		return {
			statusCode: 403,
			body: JSON.stringify({ error: 'you are not a participant in this match' }),
		};
	}

	// The draw proof.
	//
	// The commitment is visible from the moment the match exists, so
	// each player can record it independently rather than taking the
	// server's word later. What OPENS it is withheld while the match is
	// pending: a commitment that ships with its own nonce commits to
	// nothing, and a spectator endpoint reading this mid-race would be
	// handing out the world.
	//
	// The candidate list inside m.draw is deliberately NOT served here.
	// It is a per-type pool depth, which is the map of where the ladder
	// is thin - it stays on the row for the season audit and goes no
	// further.
	const finished = m.status !== 'pending';
	const draw = {
		seasonId: m.draw?.seasonId ?? null,
		scheme: m.draw?.scheme ?? null,
		// 'unverifiable' means the match was drawn with no season secret
		// configured, and says so rather than looking like a pass.
		mode: m.draw?.mode ?? null,
		commitment: m.drawCommitment ?? null,
		// The pre-image, once it can do no harm. Anyone holding the
		// commitment from earlier can now recompute it and check it did
		// not move.
		reveal: finished && m.drawCommitment
			? {
				nonce: m.drawNonce ?? null,
				seedPairId: m.seedPairId ?? null,
				overworldSeed: m.overworldSeed ?? null,
				netherSeed: m.netherSeed ?? null,
			}
			: null,
	};

	const splits: Record<string, Record<string, number>> = m.splits ?? {};

	// One row per split IN ROUTE ORDER, not in whatever order they were
	// reported. A table that reorders itself by who got there first is
	// unreadable.
	const rows = SPLIT_ORDER.map((name) => {
		const times: Record<string, number | null> = {};
		for (const p of players) {
			const t = splits[p.uuid]?.[name];
			times[p.uuid] = typeof t === 'number' ? t : null;
		}
		// The delta only means something when BOTH reached the split.
		// Against a player who never got there it is not "ahead by four
		// minutes", it is not comparable, and saying so is more honest
		// than a large green number.
		const values = players.map((p) => times[p.uuid]);
		const bothReached = values.every((v) => v !== null);
		return {
			split: name,
			times,
			deltas: bothReached
				? Object.fromEntries(players.map((p) => {
					const mine = times[p.uuid]!;
					const theirs = values.find((v) => v !== times[p.uuid]) ?? mine;
					return [p.uuid, mine - (theirs as number)];
				}))
				: null,
		};
	});

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matchId,
			players,
			winnerUuid: m.winnerUuid ?? null,
			status: m.status,
			seedType: m.seedType,
			completedAt: m.completedAt ?? null,
			forfeited: m.forfeited ?? false,
			worldSetupVersion: m.worldSetupVersion ?? 0,
			// uuid -> "id@version" strings, for the players in this match
			// only. Participants already see each other's names, splits
			// and traces; what somebody had installed is the same kind of
			// fact and is the one both sides need to settle an argument
			// about it. A uuid absent from the map reported nothing.
			mods: m.mods ?? {},
			results: m.results ?? {},
			draw,
			rows,
		}),
	};
};
