import { paceFinishMs, Schedule } from './paceSchedule';
import { isSyntheticPlayer } from './seedPool';
import { applyMatchCompletion, MatchPlayer } from './matchCompletion';

/**
 * When the pace in a paced match reaches the fountain, in epoch ms - or
 * null if this is not a paced match.
 *
 * Counted from the player's run start, the same anchor liveMatch reveals
 * the pace's splits against. A player who never started the run has no
 * run start; the match's creation stands in, which can only be earlier,
 * and a run that never started has no clock to be early against.
 */
export function paceDueAt(match: Record<string, any>): number | null {
	const players: MatchPlayer[] = match.players ?? [];
	const pace = players.find((p) => isSyntheticPlayer(p.uuid));
	const player = players.find((p) => !isSyntheticPlayer(p.uuid));
	const sched = match.paceSchedule as Schedule | undefined;
	if (!pace || !player || !sched) {
		return null;
	}
	const runStart = (match.runStarts ?? {})[player.uuid];
	const from = typeof runStart === 'number' && runStart > 0 ? runStart : match.createdAt;
	if (typeof from !== 'number') {
		return null;
	}
	return from + paceFinishMs(sched);
}

/**
 * Ends a pending paced match whose pace has already finished: the pace
 * wins, as it would have had the player been there to see it. True if
 * it did.
 *
 * liveMatch used to be the only thing that could end one, and it only
 * runs while the client polls. Quit the game mid-race and nothing ever
 * polled that match again - it stayed pending, and Race a Pace refused
 * every later attempt with "already in a match" (2026-10-02, a restart
 * to install an update). So the join paths settle it too, before asking
 * whether the player is busy.
 */
export async function settleIfPaceDue(
	matchesTableName: string,
	playersTableName: string,
	historyTableName: string,
	match: Record<string, any>,
	now: number = Date.now(),
): Promise<boolean> {
	if (match.status !== 'pending') {
		return false;
	}
	const due = paceDueAt(match);
	if (due === null || now < due) {
		return false;
	}
	const players: MatchPlayer[] = match.players;
	const pace = players.find((p) => isSyntheticPlayer(p.uuid))!;
	const player = players.find((p) => !isSyntheticPlayer(p.uuid))!;
	console.log(`match ${match.matchId}: pace ${pace.username} finished - ${player.username} did not beat it`);
	await applyMatchCompletion(
		matchesTableName, playersTableName, String(match.matchId),
		pace, player, match.splits ?? {}, historyTableName);
	return true;
}
