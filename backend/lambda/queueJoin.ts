import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import {
	DeleteCommand,
	DynamoDBDocumentClient,
	GetCommand,
	PutCommand,
	ScanCommand,
	UpdateCommand,
} from '@aws-sdk/lib-dynamodb';
import { randomUUID } from 'crypto';
import { resolveSessionToken } from './lib/auth';
import { claimSeedPair } from './lib/seedPool';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const QUEUE_TABLE_NAME = process.env.QUEUE_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const SEED_POOL_TABLE_NAME = process.env.SEED_POOL_TABLE_NAME!;

// Skill-range matchmaking: our own documented policy (not a copy of any
// other platform's undisclosed formula). Range widens the longer a
// waiting candidate has been queued, capped at MAX_RANGE (effectively
// open matchmaking after ~45s of waiting) - uses the CANDIDATE's wait
// time specifically, since the point is "how much should we relax
// match quality for someone who's already been waiting a while", not
// the freshly-joining player's (whose wait is always ~0).
const BASE_RATING_RANGE = 50;
const RANGE_GROWTH_PER_SECOND = 10;
const MAX_RATING_RANGE = 500;

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const uuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	const player = await ddb.send(new GetCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid },
	}));
	if (!player.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'no player record for this session' }) };
	}
	const myRating: number = player.Item.skillRating;

	// A player whose opponent created the match must still be told about
	// it. Only the caller that finds an opponent gets matched:true, so
	// without this the other side sees an empty queue, re-queues itself
	// and waits forever while its match sits pending.
	const existingMatchId: string | undefined = player.Item.currentMatchId;
	if (existingMatchId) {
		const existing = await ddb.send(new GetCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId: existingMatchId },
		}));
		if (existing.Item && existing.Item.status === 'pending') {
			const them = (existing.Item.players as any[]).find((p) => p.uuid !== uuid);
			// Drop any stale queue row so this player isn't matched twice.
			await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid } }));
			return {
				statusCode: 200,
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					matched: true,
					matchId: existingMatchId,
					opponent: { uuid: them.uuid, username: them.username },
					overworldSeed: existing.Item.overworldSeed,
					netherSeed: existing.Item.netherSeed,
				}),
			};
		}
	}

	// Full table Scan is a known simplification, fine at test scale - see
	// the seed pool / matches tables for the same reasoning elsewhere in
	// this backend.
	const waiting = await ddb.send(new ScanCommand({ TableName: QUEUE_TABLE_NAME }));
	const now = Date.now();

	let bestOpponent: any = null;
	let bestDiff = Infinity;
	for (const candidate of waiting.Items ?? []) {
		if (candidate.uuid === uuid) {
			continue; // can't match with yourself
		}
		const waitSeconds = (now - candidate.joinedAt) / 1000;
		const allowedRange = Math.min(
			MAX_RATING_RANGE,
			BASE_RATING_RANGE + waitSeconds * RANGE_GROWTH_PER_SECOND,
		);
		const diff = Math.abs(candidate.skillRating - myRating);
		if (diff <= allowedRange && diff < bestDiff) {
			bestOpponent = candidate;
			bestDiff = diff;
		}
	}

	if (!bestOpponent) {
		// if_not_exists on joinedAt: repeated polls from the same player
		// while waiting must not reset their wait-time clock, or the
		// range-widening above would never actually widen for them.
		await ddb.send(new UpdateCommand({
			TableName: QUEUE_TABLE_NAME,
			Key: { uuid },
			UpdateExpression: 'SET username = :username, skillRating = :rating, '
				+ 'joinedAt = if_not_exists(joinedAt, :now)',
			ExpressionAttributeValues: {
				':username': player.Item.username,
				':rating': myRating,
				':now': now,
			},
		}));
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ matched: false }),
		};
	}

	const matchId = randomUUID();

	// Claim the seed before persisting the match - if the pool is
	// exhausted, fail loudly rather than create a match nobody can
	// actually play.
	const seedPair = await claimSeedPair(SEED_POOL_TABLE_NAME, matchId);
	if (!seedPair) {
		return { statusCode: 503, body: JSON.stringify({ error: 'no seed pairs available' }) };
	}

	await ddb.send(new PutCommand({
		TableName: MATCHES_TABLE_NAME,
		Item: {
			matchId,
			players: [
				{ uuid, username: player.Item.username, skillRating: myRating },
				{ uuid: bestOpponent.uuid, username: bestOpponent.username, skillRating: bestOpponent.skillRating },
			],
			ratingDiff: bestDiff,
			overworldSeed: seedPair.overworldSeed,
			netherSeed: seedPair.netherSeed,
			status: 'pending',
			createdAt: now,
		},
	}));
	// Delete both sides - the caller may already have a row from an
	// earlier unmatched poll (the normal case for anyone who polls more
	// than once). DeleteItem on a nonexistent key is a harmless no-op, so
	// this is safe even for a caller matching on their very first call.
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid: bestOpponent.uuid } }));
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid } }));

	// Record the match on both players so the opponent - who never sees
	// this response - can discover it on their next poll.
	for (const participant of [uuid, bestOpponent.uuid]) {
		await ddb.send(new UpdateCommand({
			TableName: PLAYERS_TABLE_NAME,
			Key: { uuid: participant },
			UpdateExpression: 'SET currentMatchId = :m',
			ExpressionAttributeValues: { ':m': matchId },
		}));
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matched: true,
			matchId,
			opponent: { uuid: bestOpponent.uuid, username: bestOpponent.username },
			overworldSeed: seedPair.overworldSeed,
			netherSeed: seedPair.netherSeed,
		}),
	};
};
