import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

export interface SeedPair {
	seedPairId: string;
	overworldSeed: number;
	netherSeed: number;
}

// Atomically claims one unused seed pair for a match. Scans for
// candidates, then races a conditional update against each in turn -
// the condition (used = false) means a concurrent claim by another
// match loses cleanly instead of double-assigning the same seed.
// Fine at MVP scale; would need a smarter allocation strategy (or a
// bigger pool + random start point) once concurrent matches are common
// enough for repeated contention on the same few candidates to matter.
export async function claimSeedPair(
	seedPoolTableName: string,
	matchId: string,
): Promise<SeedPair | null> {
	const candidates = await ddb.send(new ScanCommand({
		TableName: seedPoolTableName,
		FilterExpression: '#used = :false',
		ExpressionAttributeNames: { '#used': 'used' },
		ExpressionAttributeValues: { ':false': false },
		Limit: 25,
	}));

	for (const item of candidates.Items ?? []) {
		try {
			await ddb.send(new UpdateCommand({
				TableName: seedPoolTableName,
				Key: { seedPairId: item.seedPairId },
				UpdateExpression: 'SET #used = :true, assignedMatchId = :matchId',
				ConditionExpression: '#used = :false',
				ExpressionAttributeNames: { '#used': 'used' },
				ExpressionAttributeValues: { ':true': true, ':false': false, ':matchId': matchId },
			}));
			return {
				seedPairId: item.seedPairId,
				overworldSeed: item.overworldSeed,
				netherSeed: item.netherSeed,
			};
		} catch (err) {
			if (err instanceof ConditionalCheckFailedException) {
				continue; // someone else claimed it first - try the next candidate
			}
			throw err;
		}
	}

	return null;
}
