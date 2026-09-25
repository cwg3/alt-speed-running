// The ladder standings.
//
//   GET /leaderboard?limit=50
//
// Ranked by skill rating, which is the number the ladder actually moves.
// Season points are shown alongside because they answer a different
// question - rating is how good you are, points are how much you played
// this season - and a board showing only one of them invites the wrong
// comparison.
//
// WHY A SCAN. The players table is keyed by uuid with no secondary
// index, so there is no way to read it in rating order. At this size a
// scan is the honest answer: it is one page, it costs almost nothing,
// and adding a GSI to sort a handful of rows would be machinery in place
// of arithmetic. That stops being true somewhere in the low thousands of
// players - the scan cost grows with the table while the page returned
// stays the same size - and the fix then is a GSI on a constant
// partition key sorted by rating, not a bigger scan. The limit below
// exists so the response does not grow without bound in the meantime.
//
// WHY BOTS ARE EXCLUDED. PaceBot exists to give a solo player something
// to race. Its rating moves like anyone's, so left in it would occupy a
// rank on a ladder it is not competing on, and with few humans playing
// it could top it. It is filtered by uuid prefix and the response says
// how many were removed, because silently dropping rows from a board
// that claims to be everyone is worse than the rows being there.
import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

/** Bots race, but they do not rank. */
const BOT_UUID_PREFIX = 'bot-';

interface Row {
	rank: number;
	uuid: string;
	username: string;
	skillRating: number;
	seasonPoints: number;
	wins: number;
	losses: number;
	forfeits: number;
	matches: number;
}

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	// Deliberately public: no session token required. The standings are
	// the most ordinary thing a ladder publishes, every field on them is
	// already shown to both players after a match, and requiring a login
	// to see who is winning would make the board useless to the people
	// deciding whether to ask for an invite.
	const q = event.queryStringParameters ?? {};
	const limit = Math.min(MAX_LIMIT, Math.max(1, parseInt(q.limit ?? '', 10) || DEFAULT_LIMIT));

	const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

	try {
		const players: Record<string, unknown>[] = [];
		let startKey: Record<string, unknown> | undefined;
		// Paginate on LastEvaluatedKey. A filtered or projected scan can
		// return a short page while rows remain, so stopping at the first
		// small page would quietly truncate the board.
		do {
			const page = await ddb.send(new ScanCommand({
				TableName: PLAYERS_TABLE_NAME,
				ProjectionExpression:
					'#u, username, skillRating, seasonPoints, wins, losses, forfeits, matches, allowed',
				ExpressionAttributeNames: { '#u': 'uuid' },
				ExclusiveStartKey: startKey,
			}));
			players.push(...((page.Items ?? []) as Record<string, unknown>[]));
			startKey = page.LastEvaluatedKey as Record<string, unknown> | undefined;
		} while (startKey);

		let botsHidden = 0;
		const eligible = players.filter((p) => {
			const uuid = String(p.uuid ?? '');
			if (uuid.startsWith(BOT_UUID_PREFIX)) {
				botsHidden += 1;
				return false;
			}
			// A player who has never finished a match has a rating but has
			// not earned a place on the board yet. Listing them as "last"
			// reads as a result when it is an absence of one.
			return n(p.matches) > 0;
		});

		eligible.sort((a, b) =>
			n(b.skillRating) - n(a.skillRating)
			|| n(b.seasonPoints) - n(a.seasonPoints)
			|| String(a.username ?? '').localeCompare(String(b.username ?? '')));

		// Equal ratings share a rank, and the next rank skips - standard
		// competition ranking. Two players on 1500 are joint 1st and the
		// next is 3rd, because calling one of them 2nd asserts an order
		// the rating does not support.
		const rows: Row[] = [];
		let lastRating: number | null = null;
		let lastRank = 0;
		eligible.slice(0, limit).forEach((p, i) => {
			const rating = n(p.skillRating);
			const rank = rating === lastRating ? lastRank : i + 1;
			lastRating = rating;
			lastRank = rank;
			rows.push({
				rank,
				uuid: String(p.uuid ?? ''),
				username: String(p.username ?? '?'),
				skillRating: rating,
				seasonPoints: n(p.seasonPoints),
				wins: n(p.wins),
				losses: n(p.losses),
				forfeits: n(p.forfeits),
				matches: n(p.matches),
			});
		});

		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({
				rows,
				// Said out loud rather than left for someone to notice the
				// numbers do not add up.
				totalRanked: eligible.length,
				unranked: players.length - eligible.length - botsHidden,
				botsHidden,
			}),
		};
	} catch (err) {
		console.error('[leaderboard] failed', err);
		return { statusCode: 500, body: JSON.stringify({ error: 'could not read the standings' }) };
	}
};
