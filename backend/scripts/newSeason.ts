// Opens a season by committing to the randomness every draw will use.
//
//   npx tsx scripts/newSeason.ts <season-id> [--force]
//
// Writes backend/season.local.json - gitignored, like the split rules -
// and prints the SHA256 of the secret. THAT HASH GOES IN SEASONS.md AND
// GETS PUSHED, before the first match of the season is played. The
// commit is the commitment: it is timestamped by GitHub, it is in every
// clone, and it cannot be revised afterwards without a history rewrite
// that everybody can see.
//
// What the order is:
//
//   1. this script                      (secret exists, locally)
//   2. SEASONS.md updated and PUSHED    (the commitment is public)
//   3. cdk deploy                       (the backend starts using it)
//   4. matches                          (drawn from the committed secret)
//   5. season close: publish the secret (anyone can now check every draw)
//
// Doing 3 before 2 would mean matches drawn from a secret nobody had
// committed to, which proves nothing at all - the operator could roll a
// new secret afterwards and publish whichever one made the season look
// right. The order is the whole mechanism.
//
// Back the file up to S3 under secrets/ beside headroom.local.json.
// LOSING IT LOSES THE SEASON'S PROOF: the draws stay correct but become
// permanently uncheckable, which is indistinguishable from never having
// done this.
import * as fs from 'fs';
import * as path from 'path';
import { randomBytes } from 'node:crypto';
import { seasonCommitment } from '../lambda/lib/drawProof';

const seasonId = process.argv[2];
const force = process.argv.includes('--force');

if (!seasonId || seasonId.startsWith('-')) {
	console.error('usage: npx tsx scripts/newSeason.ts <season-id> [--force]');
	process.exit(2);
}

const file = path.join(__dirname, '..', 'season.local.json');
if (fs.existsSync(file) && !force) {
	// Overwriting a live season's secret destroys the ability to check
	// every draw made under it, and the draws themselves would change
	// mid-season. Worth a deliberate flag.
	const current = JSON.parse(fs.readFileSync(file, 'utf8'));
	console.error(`season.local.json already holds season "${current.seasonId}".`);
	console.error('Overwriting it makes every draw of that season permanently');
	console.error('uncheckable. Back it up first, then pass --force.');
	process.exit(1);
}

const secret = randomBytes(32).toString('hex');
const startedAt = new Date().toISOString();
fs.writeFileSync(file, JSON.stringify({ seasonId, secret, startedAt }, null, 2) + '\n');
// The secret is the one thing in this repo's working tree that must not
// be world-readable; the file is written before the mode is set, so set
// it rather than assuming the umask did.
fs.chmodSync(file, 0o600);

const commitment = seasonCommitment(secret);

console.log(`wrote ${file} (mode 600)`);
console.log('');
console.log('Paste this row into SEASONS.md, commit and PUSH it BEFORE deploying:');
console.log('');
console.log(`| ${seasonId} | ${startedAt.slice(0, 10)} | \`${commitment}\` | open |`);
console.log('');
console.log('Then: cp season.local.json to the S3 secrets/ prefix, and deploy.');
