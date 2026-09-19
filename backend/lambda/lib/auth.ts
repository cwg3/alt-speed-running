import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * Resolves a bearer session token (issued by /auth/verify) to the uuid it
 * belongs to, or null if missing/invalid/expired. Expired rows may still
 * be physically present for a short window before DynamoDB's TTL sweep
 * deletes them, so we check expiresAt ourselves rather than trusting
 * "row exists" alone.
 */
export async function resolveSessionToken(
	sessionsTableName: string,
	authorizationHeader: string | undefined,
): Promise<string | null> {
	if (!authorizationHeader?.startsWith('Bearer ')) {
		return null;
	}
	const token = authorizationHeader.slice('Bearer '.length).trim();
	if (!token) {
		return null;
	}

	const result = await ddb.send(new GetCommand({
		TableName: sessionsTableName,
		Key: { token },
	}));

	if (!result.Item) {
		return null;
	}
	if (result.Item.expiresAt < Math.floor(Date.now() / 1000)) {
		return null;
	}
	return result.Item.uuid as string;
}
