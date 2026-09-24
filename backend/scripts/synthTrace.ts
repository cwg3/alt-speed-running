// Builds a plausible replay trace for the pace bot.
//
//   npx tsx scripts/synthTrace.ts <finishSeconds> [hz]
//
// Prints the packed sample rows on stdout for pace-bot.sh to upload.
//
// The bot posts splits but has never uploaded a trace, so every replay
// in the system is single-perspective and the headline feature - two
// first-person views you can switch between - has nothing to test
// against.
//
// This is deliberately not random noise. It has to be PLAUSIBLE:
//
//   - in the right dimension when the bot claims that split, because a
//     replay showing the nether split happening in the overworld is
//     worse than no replay
//   - moving at speeds a player could move, under the same limits
//     replayChecks applies to real traces
//   - looking roughly where it is going, since the whole point is
//     first-person playback
//
// Making it plausible is the useful part. "What would a real trace look
// like" is the same question the anti-cheat asks, so getting this wrong
// in an obvious way tends to reveal that the checks are too loose.
const OVERWORLD = 0, NETHER = 1, END = 2;

/** Comfortably inside replayChecks' 20 m/s ceiling. */
const WALK = 5.5;

/** Fractions of the finish time, matching pace-bot's own schedule. */
const SPLITS: [string, number, number][] = [
	// name, fraction, dimension AFTER this point
	['enter_nether', 0.167, NETHER],
	['piglin_barter', 0.250, NETHER],
	['obtain_rod', 0.417, NETHER],
	['enter_stronghold', 0.708, OVERWORLD],
	['enter_end', 0.833, END],
	['kill_dragon', 1.0, END],
];

function dimensionAt(tSec: number, finish: number): number {
	let dim = OVERWORLD;
	for (const [, frac, d] of SPLITS) {
		if (tSec >= frac * finish) dim = d;
	}
	return dim;
}

function main() {
	const finish = parseFloat(process.argv[2] ?? '240');
	const hz = parseInt(process.argv[3] ?? '10', 10);
	const step = 1 / hz;

	const rows: string[] = [];
	let x = 0, z = 0, y = 64;
	let heading = 0;
	let prevDim = OVERWORLD;

	for (let t = 0; t <= finish; t += step) {
		const dim = dimensionAt(t, finish);

		if (dim !== prevDim) {
			// A dimension change is a teleport, not travel. Jump the
			// coordinates rather than sprinting between them - the
			// speed check skips cross-dimension steps for exactly this
			// reason, and a trace that walked there would be a lie.
			if (dim === NETHER) {
				x = Math.round(x / 8); z = Math.round(z / 8); y = 70;
			} else if (prevDim === NETHER) {
				x = x * 8; z = z * 8; y = 64;
			} else if (dim === END) {
				x = 100; z = 0; y = 50;
			}
			prevDim = dim;
		} else {
			// Wander with a slowly turning heading rather than a
			// straight line: a real run does not travel on a bearing
			// for ten minutes, and a straight line makes a replay look
			// obviously synthetic the moment anyone watches it.
			heading += Math.sin(t / 7) * 0.05;
			const speed = WALK * step;
			x += Math.cos(heading) * speed;
			z += Math.sin(heading) * speed;
			y += Math.sin(t / 3) * 0.05;
		}

		// Looking where it is going, with a little drift.
		const yaw = Math.round(((heading * 180) / Math.PI) % 360);
		const pitch = Math.round(Math.sin(t / 5) * 12);

		rows.push(`[${Math.round(t * 1000)},${dim},`
			+ `${(Math.round(x * 10) / 10)},${(Math.round(y * 10) / 10)},`
			+ `${(Math.round(z * 10) / 10)},${yaw},${pitch}]`);
	}

	process.stdout.write(`[${rows.join(',')}]`);
}

main();
