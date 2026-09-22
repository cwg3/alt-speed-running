import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand, ScanCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const SEED_POOL_TABLE_NAME = process.env.SEED_POOL_TABLE_NAME!;

/**
 * Votes that a match's seed is unplayable. Takes BOTH players.
 *
 * Some seeds cannot be run, and the filter will not catch all of them -
 * today's examples include a village with no blacksmith chest and a
 * desert temple with no tree within five chunks to craft with. Without
 * a way out, the player who drew it either grinds a hopeless run or
 * forfeits and eats the rating loss for the pool's mistake.
 *
 * The obvious fix - let one player void the match - is an obvious
 * exploit: a player losing on a perfectly good seed calls it bad and
 * escapes. So it takes both. Agreement between opponents who are
 * actively racing each other is hard to fake, and it needs no
 * moderator, which is the point of this project: the rule is mechanical
 * and its result is visible to both sides.
 *
 * A voided match moves NO ratings. It did not happen. Neither player
 * won, and neither is punished for a seed we shipped.
 *
 * The seed is quarantined rather than released, along with who voided
 * it and when, so a bad seed is out of circulation immediately and the
 * decision stays auditable afterwards.
 *
 * NOT timed out on purpose. If the opponent never agrees the match
 * simply carries on - a proposal that expired into a void would let a
 * player escape by proposing and then vanishing.
 */
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const uuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	let body: { matchId?: string; reason?: string };
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}
	if (!body.matchId) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId is required' }) };
	}
	const matchId = body.matchId;
	const reason = (body.reason ?? '').slice(0, 200);

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players ?? [];
	const me = players.find((p) => p.uuid === uuid);
	const opponent = players.find((p) => p.uuid !== uuid);
	if (!me || !opponent) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}
	if (match.Item.status !== 'pending') {
		return { statusCode: 409, body: JSON.stringify({ error: 'match is already over' }) };
	}

	const now = Date.now();
	const existing: Record<string, { at: number; reason: string }> = match.Item.badSeedVotes ?? {};

	// Record this player's vote. attribute_not_exists keeps the FIRST
	// vote's timestamp and reason rather than letting a second click
	// overwrite them - the record of who raised it first is the
	// interesting part.
	if (!existing[uuid]) {
		await ddb.send(new UpdateCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId },
			UpdateExpression: 'SET badSeedVotes = if_not_exists(badSeedVotes, :empty)',
			ExpressionAttributeValues: { ':empty': {} },
		}));
		try {
			await ddb.send(new UpdateCommand({
				TableName: MATCHES_TABLE_NAME,
				Key: { matchId },
				UpdateExpression: 'SET badSeedVotes.#uuid = :vote',
				ConditionExpression: 'attribute_not_exists(badSeedVotes.#uuid)',
				ExpressionAttributeNames: { '#uuid': uuid },
				ExpressionAttributeValues: { ':vote': { at: now, reason } },
			}));
		} catch (err) {
			// A double click races itself; the first write wins and the
			// second losing the condition is not an error.
			if ((err as { name?: string }).name !== 'ConditionalCheckFailedException') {
				throw err;
			}
		}
		existing[uuid] = { at: now, reason };
	}

	const bothAgreed = Boolean(existing[uuid] && existing[opponent.uuid]);
	if (!bothAgreed) {
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({
				voided: false,
				yourVote: true,
				opponentVote: false,
				message: 'waiting for your opponent to agree',
			}),
		};
	}

	// Both agreed. Void the match - no winner, no rating movement.
	// Conditional on status so a dragon kill landing in the same instant
	// cannot be overwritten by a void, or vice versa.
	try {
		await ddb.send(new UpdateCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId },
			UpdateExpression:
				'SET #s = :void, completedAt = :now, voidReason = :reason',
			ConditionExpression: '#s = :pending',
			ExpressionAttributeNames: { '#s': 'status' },
			ExpressionAttributeValues: {
				':void': 'voided',
				':pending': 'pending',
				':now': now,
				':reason': 'bad seed, agreed by both players',
			},
		}));
	} catch (err) {
		if ((err as { name?: string }).name === 'ConditionalCheckFailedException') {
			return { statusCode: 409, body: JSON.stringify({ error: 'match ended before the vote completed' }) };
		}
		throw err;
	}

	// Every player's own words, not just whoever happened to vote
	// last. The first real vote recorded the synthetic opponent's
	// "test vote from PaceBot" and threw away what the player said,
	// because only the completing caller's reason was kept.
	await quarantineSeed(matchId, existing);

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			voided: true,
			yourVote: true,
			opponentVote: true,
			message: 'both players agreed - match voided, no rating change',
		}),
	};
};

/**
 * Takes the seed out of circulation permanently.
 *
 * Marked used AND flagged, rather than deleted: a pool that silently
 * shrinks tells you nothing, while a row that says why it was pulled
 * can be looked at later to work out what the filter missed. Every bad
 * seed found this way is a filter bug with evidence attached.
 */
async function quarantineSeed(
	matchId: string,
	votes: Record<string, { at: number; reason: string }>,
) {
	const voters = Object.keys(votes);
	const reasons = voters
		.map((uuid) => votes[uuid]?.reason?.trim())
		.filter((r): r is string => Boolean(r));
	const reason = reasons.length ? reasons.join(' | ') : '(no reason given)';
	const found = await ddb.send(new ScanCommand({
		TableName: SEED_POOL_TABLE_NAME,
		FilterExpression: 'assignedMatchId = :m',
		ExpressionAttributeValues: { ':m': matchId },
		ProjectionExpression: 'seedPairId',
	}));
	const row = found.Items?.[0];
	if (!row) {
		// Nothing to quarantine - the pair may predate assignment
		// tracking. The match is still voided either way.
		console.warn(`No seed pool row found for match ${matchId}`);
		return;
	}
	await ddb.send(new UpdateCommand({
		TableName: SEED_POOL_TABLE_NAME,
		Key: { seedPairId: row.seedPairId },
		UpdateExpression:
			'SET used = :true, badSeed = :true, badSeedAt = :now, badSeedVoters = :voters, badSeedReason = :reason',
		ExpressionAttributeValues: {
			':true': true,
			':now': Date.now(),
			':voters': voters,
			':reason': reason,
		},
	}));
}
