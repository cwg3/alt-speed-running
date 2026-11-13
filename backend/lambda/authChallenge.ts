import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand } from '@aws-sdk/lib-dynamodb';
import { randomBytes } from 'crypto';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const CHALLENGES_TABLE_NAME = process.env.CHALLENGES_TABLE_NAME!;

/**
 * How long an issued serverId can be redeemed at /auth/verify.
 *
 * The client fetches one, joins Mojang with it and verifies straight
 * away - a few seconds end to end, longer on a cold start. Two minutes
 * covers that with room, and is short enough that a leaked id is
 * worthless by the time anyone could do anything with it.
 */
export const CHALLENGE_TTL_MS = 2 * 60 * 1000;

/**
 * A serverId for the Mojang join/hasJoined handshake.
 *
 * 16 hex characters. Mojang limits serverId to 20 characters and
 * rejects '-' and '_', which is how the client's old base64url ids
 * failed intermittently - see SessionAuth.java.
 */
export function newServerId(): string {
	return randomBytes(8).toString('hex');
}

// Issues the serverId a client must join Mojang with before logging in.
//
// The client used to make one up. That let anyone who learned a
// player's serverId log in as them, and every Minecraft server a player
// joins learns one: in the vanilla handshake the server computes the
// same hash the client sends Mojang. A hostile server could replay it
// at /auth/verify within hasJoined's window and walk away with a 30-day
// session in that player's name.
//
// An id we issued, redeemed once, closes that. A vanilla server's hash
// is never one of ours, and a stolen one of ours is spent by the
// player's own verify a second after it was issued.
export const handler = async (
	_event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const serverId = newServerId();
	const now = Date.now();
	await ddb.send(new PutCommand({
		TableName: CHALLENGES_TABLE_NAME,
		Item: {
			serverId,
			// Checked by verifySession, because DynamoDB's TTL reaper
			// runs when it likes - an expired row can sit for days.
			expiresAtMs: now + CHALLENGE_TTL_MS,
			// Seconds, for the TTL reaper, which only keeps the table
			// from growing.
			expiresAt: Math.floor((now + CHALLENGE_TTL_MS) / 1000),
		},
	}));
	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ serverId }),
	};
};
