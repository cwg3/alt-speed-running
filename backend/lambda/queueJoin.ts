import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import {
	DeleteCommand,
	DynamoDBDocumentClient,
	GetCommand,
	PutCommand,
	ScanCommand,
} from '@aws-sdk/lib-dynamodb';
import { randomUUID } from 'crypto';
import { resolveSessionToken } from './lib/auth';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const QUEUE_TABLE_NAME = process.env.QUEUE_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

// MVP pairing only: match with the first other waiting player found, no
// skill-range matching yet (not worth building until there's an actual
// population to match within). Also uses a full table Scan, which is
// fine at test scale but won't be once the queue is more than a handful
// of rows - a known simplification, not an oversight.
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

	// Look for another waiting player before adding ourselves, so a
	// player can't be paired with themselves.
	const waiting = await ddb.send(new ScanCommand({ TableName: QUEUE_TABLE_NAME }));
	const opponent = (waiting.Items ?? []).find((item) => item.uuid !== uuid);

	if (!opponent) {
		await ddb.send(new PutCommand({
			TableName: QUEUE_TABLE_NAME,
			Item: { uuid, username: player.Item.username, joinedAt: Date.now() },
		}));
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ matched: false }),
		};
	}

	const matchId = randomUUID();
	await ddb.send(new PutCommand({
		TableName: MATCHES_TABLE_NAME,
		Item: {
			matchId,
			players: [
				{ uuid, username: player.Item.username },
				{ uuid: opponent.uuid, username: opponent.username },
			],
			status: 'pending',
			createdAt: Date.now(),
		},
	}));
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid: opponent.uuid } }));

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matched: true,
			matchId,
			opponent: { uuid: opponent.uuid, username: opponent.username },
		}),
	};
};
