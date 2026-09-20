/**
 * Automated checks over a match's position timeline.
 *
 * The point of storing a replay is not archival - it is that reported
 * splits can be corroborated against a continuous record. A client can
 * claim it entered the nether at 2:00; the timeline either shows it
 * standing in the nether at 2:00 or it doesn't.
 *
 * Same stance as the statistical review: these produce findings for a
 * human, never an automatic ban. And like everything else here, each
 * finding explains itself.
 */

export const OVERWORLD = 0;
export const NETHER = 1;
export const END = 2;

/** [elapsedMs, dimension, x, y, z] */
export type Sample = [number, number, number, number, number];

/**
 * Fastest sustained travel in the overworld. Sprint-jumping is roughly
 * 7.1 m/s; this allows generous headroom for ice, boats and downhill
 * momentum before calling anything impossible.
 */
const MAX_OVERWORLD_SPEED = 20;

/**
 * Nether coordinates compress 8:1 against the overworld, but movement
 * *within* the nether is still ordinary walking speed. The allowance
 * here is for the same reasons as above, not for the coordinate ratio.
 */
const MAX_NETHER_SPEED = 20;

/**
 * Ender pearls and portals move a player instantly, so isolated jumps
 * are normal and expected. Only sustained impossible movement counts -
 * a single large step is ignored, and the check looks at whether high
 * speed persists across consecutive samples.
 */
const SUSTAINED_SAMPLES = 3;

export interface ReplayFinding {
	kind: string;
	atMs: number;
	note: string;
}

export interface ReplayCheckResult {
	sampleCount: number;
	durationMs: number;
	findings: ReplayFinding[];
}

function speedBetween(a: Sample, b: Sample): number | null {
	const dt = (b[0] - a[0]) / 1000;
	if (dt <= 0) {
		return null;
	}
	// A dimension change relocates the player legitimately.
	if (a[1] !== b[1]) {
		return null;
	}
	const dx = b[2] - a[2];
	const dy = b[3] - a[3];
	const dz = b[4] - a[4];
	return Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
}

function limitFor(dim: number): number {
	return dim === NETHER ? MAX_NETHER_SPEED : MAX_OVERWORLD_SPEED;
}

/**
 * Checks the timeline on its own terms, then against the reported
 * splits.
 */
export function checkReplay(
	samples: Sample[],
	splits: Record<string, number>,
): ReplayCheckResult {
	const findings: ReplayFinding[] = [];

	// Sustained impossible movement. Counting consecutive over-limit
	// steps is what separates a speed hack from an ender pearl.
	let run = 0;
	let runStart = 0;
	for (let i = 1; i < samples.length; i++) {
		const speed = speedBetween(samples[i - 1], samples[i]);
		const limit = limitFor(samples[i][1]);
		if (speed !== null && speed > limit) {
			if (run === 0) {
				runStart = samples[i - 1][0];
			}
			run++;
			if (run === SUSTAINED_SAMPLES) {
				findings.push({
					kind: 'sustained_speed',
					atMs: runStart,
					note: `moved faster than ${limit} m/s for ${SUSTAINED_SAMPLES} consecutive `
						+ `seconds starting at ${Math.round(runStart / 1000)}s `
						+ `(${speed.toFixed(1)} m/s) - isolated jumps from pearls or portals `
						+ `are ignored, this persisted`,
				});
			}
		} else {
			run = 0;
		}
	}

	// Splits must agree with where the player actually was.
	const dimensionExpectations: { split: string; dim: number; label: string }[] = [
		{ split: 'enter_nether', dim: NETHER, label: 'the nether' },
		{ split: 'enter_end', dim: END, label: 'the end' },
		{ split: 'kill_dragon', dim: END, label: 'the end' },
	];

	for (const { split, dim, label } of dimensionExpectations) {
		const claimed = splits[split];
		if (claimed === undefined || samples.length === 0) {
			continue;
		}
		const at = nearestSample(samples, claimed);
		if (!at) {
			continue;
		}
		// Tolerance for the sampling interval either side of the claim.
		const withinWindow = samples.filter((s) => Math.abs(s[0] - claimed) <= 3000);
		if (withinWindow.length > 0 && !withinWindow.some((s) => s[1] === dim)) {
			findings.push({
				kind: 'dimension_mismatch',
				atMs: claimed,
				note: `${split} was reported at ${Math.round(claimed / 1000)}s but the timeline `
					+ `never shows the player in ${label} around that time`,
			});
		}
	}

	// A claimed split with no timeline coverage at all can't be checked,
	// which is itself worth surfacing.
	const lastSample = samples.length ? samples[samples.length - 1][0] : 0;
	for (const [split, time] of Object.entries(splits)) {
		if (time > lastSample + 10_000) {
			findings.push({
				kind: 'no_coverage',
				atMs: time,
				note: `${split} was reported at ${Math.round(time / 1000)}s but the timeline `
					+ `ends at ${Math.round(lastSample / 1000)}s, so it cannot be corroborated`,
			});
		}
	}

	return {
		sampleCount: samples.length,
		durationMs: lastSample,
		findings,
	};
}

function nearestSample(samples: Sample[], t: number): Sample | null {
	let best: Sample | null = null;
	let bestGap = Infinity;
	for (const s of samples) {
		const gap = Math.abs(s[0] - t);
		if (gap < bestGap) {
			bestGap = gap;
			best = s;
		}
	}
	return best;
}
