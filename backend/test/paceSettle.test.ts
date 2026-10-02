const completions: any[] = [];

jest.mock('../lambda/lib/matchCompletion', () => ({
	applyMatchCompletion: (...args: any[]) => {
		completions.push(args);
		return Promise.resolve();
	},
}));

const { paceDueAt, settleIfPaceDue } = require('../lambda/lib/paceSettle');
const { isSyntheticPlayer } = require('../lambda/lib/seedPool');

/**
 * A paced match only ended when liveMatch, polled by the running client,
 * saw the pace finish. Quitting the game mid-race left it pending for
 * good, and Race a Pace answered "already in a match" from then on.
 */
describe('settling a paced match the client left behind', () => {
	const paceUuid = ['pace-30-00', 'bot-rival', 'pace:30:00']
		.find((u) => isSyntheticPlayer(u));
	const player = { uuid: 'real-player', username: 'MissVanFan' };
	const pace = { uuid: paceUuid, username: 'Pace 30:00' };
	const schedule = { enter_nether: 300_000, finish: 1_800_000 };

	const paced = (extra: Record<string, any> = {}) => ({
		matchId: 'm1',
		status: 'pending',
		createdAt: 1_000,
		players: [player, pace],
		paceSchedule: schedule,
		runStarts: { [player.uuid]: 11_000 },
		...extra,
	});

	beforeEach(() => {
		completions.length = 0;
	});

	it('has a synthetic uuid to test with', () => {
		expect(paceUuid).toBeDefined();
	});

	it('is due when the pace finishes, counted from the run start', () => {
		expect(paceDueAt(paced())).toBe(11_000 + 1_800_000);
	});

	it('counts from creation when the run never started', () => {
		expect(paceDueAt(paced({ runStarts: {} }))).toBe(1_000 + 1_800_000);
	});

	it('is not a paced match without a pace or a schedule', () => {
		expect(paceDueAt(paced({ paceSchedule: undefined }))).toBeNull();
		expect(paceDueAt(paced({ players: [player, { uuid: 'human-2', username: 'x' }] }))).toBeNull();
	});

	it('leaves a race that is still running alone', async () => {
		expect(await settleIfPaceDue('M', 'P', 'H', paced(), 11_000 + 1_799_999)).toBe(false);
		expect(completions).toHaveLength(0);
	});

	it('ends an overdue one with the pace as winner', async () => {
		expect(await settleIfPaceDue('M', 'P', 'H', paced(), 11_000 + 1_800_000)).toBe(true);
		expect(completions).toHaveLength(1);
		const [, , matchId, winner, loser, , history] = completions[0];
		expect(matchId).toBe('m1');
		expect(winner.uuid).toBe(paceUuid);
		expect(loser.uuid).toBe(player.uuid);
		expect(history).toBe('H');
	});

	it('never touches a match that is already over', async () => {
		expect(await settleIfPaceDue('M', 'P', 'H', paced({ status: 'completed' }), 9e12)).toBe(false);
		expect(completions).toHaveLength(0);
	});
});
