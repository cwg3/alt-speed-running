import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { randomBytes } from 'crypto';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;

// New players start at a standard Elo default; this is the hidden
// matchmaking rating, separate from the season points shown to players.
const DEFAULT_SKILL_RATING = 1500;
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60; // 30 days

interface VerifyRequest {
	username: string;
	serverId: string;
}

interface MojangProfile {
	id: string;
	name: string;
}

// Confirms a client's Mojang session-join actually happened, proving
// account ownership without our backend ever seeing the player's access
// token. Same mechanism vanilla multiplayer servers have used for
// identity verification for years - see mod/.../auth/SessionAuth.java
// for the client side of this pair.
//
// On success, upserts the player's account record and issues an opaque
// session token the mod uses for subsequent authenticated calls (so we
// don't need to re-verify against Mojang on every request).
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	let body: VerifyRequest;
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}

	if (!body.username || !body.serverId) {
		return {
			statusCode: 400,
			body: JSON.stringify({ error: 'username and serverId are required' }),
		};
	}

	const url = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(body.username)}&serverId=${encodeURIComponent(body.serverId)}`;
	const res = await fetch(url);

	// Confirmed live (not assumed from docs): Mojang returns HTTP 204 with
	// an empty body for a non-matching join - that's the genuine
	// "verification failed" case, not a transport error. A literal "null"
	// body has also been documented historically, so both are handled.
	if (res.status === 204) {
		return {
			statusCode: 401,
			body: JSON.stringify({ error: 'session verification failed - no matching join' }),
		};
	}

	if (res.status !== 200) {
		return {
			statusCode: 502,
			body: JSON.stringify({ error: `Mojang session server returned HTTP ${res.status}` }),
		};
	}

	const text = await res.text();
	if (!text || text.trim() === 'null') {
		return {
			statusCode: 401,
			body: JSON.stringify({ error: 'session verification failed - no matching join' }),
		};
	}

	const profile = JSON.parse(text) as MojangProfile;
	const now = Date.now();

	// Single atomic upsert: if_not_exists means a first-time login creates
	// the row with defaults, a returning login only touches username/
	// lastLoginAt - no separate get-then-put race condition.
	await ddb.send(new UpdateCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid: profile.id },
		UpdateExpression:
			'SET username = :username, lastLoginAt = :now, ' +
			'createdAt = if_not_exists(createdAt, :now), ' +
			'skillRating = if_not_exists(skillRating, :defaultRating), ' +
			'seasonPoints = if_not_exists(seasonPoints, :zero)',
		ExpressionAttributeValues: {
			':username': profile.name,
			':now': now,
			':defaultRating': DEFAULT_SKILL_RATING,
			':zero': 0,
		},
	}));

	const sessionToken = randomBytes(32).toString('base64url');
	await ddb.send(new PutCommand({
		TableName: SESSIONS_TABLE_NAME,
		Item: {
			token: sessionToken,
			uuid: profile.id,
			createdAt: now,
			expiresAt: Math.floor(now / 1000) + SESSION_TTL_SECONDS,
		},
	}));

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ uuid: profile.id, username: profile.name, sessionToken }),
	};
};
