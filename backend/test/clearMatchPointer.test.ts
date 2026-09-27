import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';

const sent: any[] = [];
let nextError: Error | null = null;

jest.mock('@aws-sdk/lib-dynamodb', () => {
	const actual = jest.requireActual('@aws-sdk/lib-dynamodb');
	return {
		...actual,
		DynamoDBDocumentClient: {
			from: () => ({
				send: (command: any) => {
					sent.push(command.input);
					if (nextError) {
						const err = nextError;
						nextError = null;
						return Promise.reject(err);
					}
					return Promise.resolve({});
				},
			}),
		},
	};
});

// Imported after the mock is registered, because the module builds its
// client at load time.
const { clearMatchPointer } = require('../lambda/lib/matchCompletion');

const conditionFailed = () => new ConditionalCheckFailedException({
	message: 'The conditional request failed',
	$metadata: {},
});

/**
 * A stale currentMatchId is not cosmetic: pacedMatch refuses to start a
 * race while one is set, so a match that ends without clearing it makes
 * Race a Pace answer "already in a match" forever. badSeed voided a
 * paced match and cleared nothing, and that is exactly what happened.
 */
describe('clearMatchPointer', () => {
	beforeEach(() => {
		sent.length = 0;
		nextError = null;
	});

	it('clears every player named', async () => {
		await clearMatchPointer('Players', ['player-a', 'bot-rival'], 'match-1');
		expect(sent).toHaveLength(2);
		expect(sent.map((i) => i.Key.uuid)).toEqual(['player-a', 'bot-rival']);
		expect(sent[0].UpdateExpression).toBe('REMOVE currentMatchId');
	});

	it('only clears a pointer that still names this match', async () => {
		// A blind REMOVE races a player who has already started their
		// next match: the pointer would be the NEW match's, and deleting
		// it would strand that one instead.
		await clearMatchPointer('Players', ['player-a'], 'match-1');
		expect(sent[0].ConditionExpression).toBe('currentMatchId = :m');
		expect(sent[0].ExpressionAttributeValues).toEqual({ ':m': 'match-1' });
	});

	it('treats a failed condition as normal, not an error', async () => {
		// It means somebody else already cleared it or moved it on. A
		// throw here would take down the caller that ended the match.
		nextError = conditionFailed();
		await expect(clearMatchPointer('Players', ['player-a'], 'match-1'))
			.resolves.toBeUndefined();
	});

	it('still clears the second player after the first is already clear', async () => {
		nextError = conditionFailed();
		await clearMatchPointer('Players', ['player-a', 'player-b'], 'match-1');
		expect(sent.map((i) => i.Key.uuid)).toEqual(['player-a', 'player-b']);
	});

	it('does not swallow a real failure', async () => {
		nextError = new Error('ProvisionedThroughputExceededException');
		await expect(clearMatchPointer('Players', ['player-a'], 'match-1'))
			.rejects.toThrow('ProvisionedThroughputExceededException');
	});
});
