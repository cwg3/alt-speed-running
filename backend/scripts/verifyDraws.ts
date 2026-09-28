// Replays every draw of a season and checks the server dealt what the
// arithmetic demanded.
//
//   npx tsx scripts/verifyDraws.ts <matches-table> [--season <file>] [--season-id <id>]
//
// This is the audit the whole commit-reveal exists to make possible. It
// takes the season secret, recomputes HMAC(secret, matchId) for every
// match, re-derives the draw order over the candidate list recorded on
// that match, and checks the seed actually dealt is the one that order
// puts first. Then it opens each match's commitment and checks it
// covers the seed the match record holds.
//
// TODAY only the operator can run this, because only the operator has
// the secret - which is worth being blunt about, since a proof only the
// accused can perform is not yet a proof. It becomes one at season
// close, when the secret is published and anybody can run this exact
// script against the same records. Until then its job is smaller: it
// catches the ladder drifting out of step with its own scheme before
// the season is published and the drift becomes permanent.
//
// WHAT IT CANNOT SEE, and nothing downstream should imply otherwise:
// the candidate list is taken on trust. A draw over a list with rows
// left out of it verifies perfectly. See lib/drawProof.ts.
//
// PRINTS NO SEED VALUES AND NO POOL DEPTHS. A per-type candidate count
// is the map of where the ladder is thin, and an audit tool that leaks
// it while proving the draw is honest would be a poor trade.
import * as fs from 'fs';
import * as path from 'path';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';
import {
	type DrawCandidate,
	commitDraw,
	derivedPicker,
	deriveDrawOrder,
	seasonCommitment,
} from '../lambda/lib/drawProof';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const args = process.argv.slice(2);
const table = args.find((a) => !a.startsWith('-'));
const flag = (name: string): string | undefined => {
	const i = args.indexOf(name);
	return i >= 0 ? args[i + 1] : undefined;
};

if (!table) {
	console.error('usage: npx tsx scripts/verifyDraws.ts <matches-table> '
		+ '[--season <file>] [--season-id <id>]');
	process.exit(2);
}

const seasonFile = flag('--season')
	?? path.join(__dirname, '..', 'season.local.json');
if (!fs.existsSync(seasonFile)) {
	console.error(`no season file at ${seasonFile}`);
	console.error('Pass --season <file>, or fetch it from the S3 secrets/ prefix.');
	process.exit(2);
}
const season = JSON.parse(fs.readFileSync(seasonFile, 'utf8')) as {
	seasonId: string; secret: string;
};
const onlySeason = flag('--season-id') ?? season.seasonId;

/** `type:id` back into the shape the derivation takes. */
function parseCandidate(key: string): DrawCandidate {
	const at = key.indexOf(':');
	return { seedType: key.slice(0, at), seedPairId: key.slice(at + 1) };
}

interface Failure { matchId: string; why: string }

async function main(): Promise<void> {
	console.log(`season      ${onlySeason}`);
	// The number to compare against SEASONS.md by eye. If these differ,
	// nothing below means anything: the secret in hand is not the one
	// that was committed to, and every check it passes is circular.
	console.log(`commitment  ${seasonCommitment(season.secret)}`);
	console.log('            ^ must equal the row in SEASONS.md');
	console.log('');

	let checked = 0;
	let unverifiable = 0;
	let otherSeason = 0;
	let noDraw = 0;
	const failures: Failure[] = [];

	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: table,
			ExclusiveStartKey: startKey,
		}));
		for (const m of page.Items ?? []) {
			const matchId = String(m.matchId);
			const draw = m.draw as {
				seasonId?: string; mode?: string; candidates?: string[]; index?: number;
			} | undefined;

			// Matches made before this scheme existed. Counted, not
			// failed - they were drawn honestly under the old rules,
			// and calling them failures would bury real ones.
			if (!draw || !Array.isArray(draw.candidates)) { noDraw++; continue; }
			if (draw.seasonId !== onlySeason) { otherSeason++; continue; }
			// Drawn with no secret configured. The record says so itself,
			// which is the point of stamping it rather than defaulting.
			if (draw.mode !== 'derived') { unverifiable++; continue; }

			checked++;

			const order = deriveDrawOrder(
				draw.candidates.map(parseCandidate),
				derivedPicker(season.secret, matchId),
			);
			const dealt = `${m.seedType}:${m.seedPairId}`;
			const index = typeof draw.index === 'number' ? draw.index : 0;
			if (order[index] !== dealt) {
				failures.push({
					matchId,
					why: `dealt seed is not what the derivation puts at index ${index}`,
				});
			} else if (index !== 0) {
				// Legal - the draw walks on when a claim fails - but it
				// has never happened since claims stopped being
				// conditional, so it is worth surfacing rather than
				// passing in silence.
				console.log(`?  ${matchId}: dealt from index ${index}, not 0`);
			}

			// The commitment, opened. Checks the record was not edited
			// after the fact: the seeds, the id and the nonce all feed
			// the hash, so moving any of them breaks it.
			if (!m.drawCommitment) {
				failures.push({ matchId, why: 'draw recorded but no commitment stored' });
			} else if (!m.drawNonce) {
				failures.push({ matchId, why: 'commitment stored but the nonce is gone - it can never be opened' });
			} else {
				const recomputed = commitDraw(onlySeason, matchId, String(m.drawNonce), {
					seedPairId: String(m.seedPairId),
					overworldSeed: Number(m.overworldSeed),
					netherSeed: Number(m.netherSeed),
				});
				if (recomputed !== m.drawCommitment) {
					failures.push({ matchId, why: 'commitment does not cover the seed on this match' });
				}
			}
		}
		startKey = page.LastEvaluatedKey;
	} while (startKey);

	console.log('');
	console.log(`checked        ${checked}`);
	console.log(`unverifiable   ${unverifiable}   (drawn with no season secret)`);
	console.log(`other seasons  ${otherSeason}`);
	console.log(`pre-scheme     ${noDraw}`);
	console.log(`FAILED         ${failures.length}`);
	for (const f of failures) {
		console.log(`!! ${f.matchId}: ${f.why}`);
	}
	// A season with nothing to check is not a season that passed. Said
	// out loud because a green run over zero matches is the exact shape
	// of a check that has quietly stopped seeing anything.
	if (checked === 0) {
		console.log('');
		console.log('NOTHING WAS CHECKED. That is not a pass - check the season id.');
	}
	process.exit(failures.length > 0 ? 1 : 0);
}

main().catch((err) => { console.error(err); process.exit(1); });
