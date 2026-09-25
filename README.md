<img src="brand/logo_alt_preview.png" alt="alt" width="200">

A 1v1 ladder for Minecraft 1.16.1 speedrunning (RSG).

Two players race the same filtered seed in their own worlds. Whoever
kills the dragon first wins. Ratings move, seasons reset.

**Status: pre-alpha.** It works end to end, but it has had very little
real play. Expect rough edges.

---

## Get started

1. **Make sure you are invited.** Pre-alpha testing is **invite-only**:
   logging in checks your Minecraft account against an allowlist.
   [Open an issue](../../issues/new) with the Minecraft username you
   want to play on, and ask for an invite.

   Do this first. The client installs fine without one and nothing
   warns you — it is **matchmaking** that refuses you, so an uninvited
   account gets all the way to Find Match before finding out.
2. **[Download the latest release](../../releases/latest)** — take
   `alt-<version>.mrpack` for Prism, MultiMC, Modrinth App or ATLauncher;
   take the two `.jar` files if you use the official Minecraft
   launcher.
3. **[Follow INSTALL.md](INSTALL.md)** for the exact steps, including
   which Fabric and Fabric API versions are required and why they are
   exact.
4. **[Read DEVIATIONS.md](DEVIATIONS.md)** before you play. A match is
   not pure vanilla and every difference is published. Anything that
   differs and is not on that list is a bug worth reporting.

The rest of this file is what the project is and how it works.

---

## Playing

1. Click **alt  speed-running** at the top of the title screen.
2. It verifies your account automatically — you should see your
   username, rating and season points. No password, no separate signup;
   it confirms ownership through Mojang using the session you are
   already logged in with.
3. Click **Find Match**. You are paired with someone near your rating.
   The range widens the longer you wait.
4. A world is generated and you are dropped straight into it. The timer
   starts.
5. Race. The HUD top-left shows your splits against your opponent's —
   green means you are ahead, red means behind.
6. First to kill the dragon wins. Ratings update automatically.

**Press F6 to forfeit** if you want out of a bad seed. It asks for
confirmation first. Your opponent wins and your rating moves as it would
for any loss, but the match is recorded and shown as a **forfeit**, not
a loss.

---

## What is different from vanilla

Both players get the same filtered seed, and some randomness is fixed so
neither player can be handed a better run by luck:

- Overworld and nether are seeded **independently**, so you cannot infer
  nether structure locations from overworld terrain
- Iron golems always drop 4 iron
- Gravel gives 2 flint per 20 blocks, on a schedule both players share
- Dead bushes always drop 2 sticks
- Shearing a sheep always gives 3 wool
- Thrown eyes of ender never break
- Ender pearls never spawn endermites
- Piglin bartering guarantees obsidian and pearls over a window of
  trades
- Blaze spawner timing is deterministic per spawner rather than being
  pulled from shared world randomness

---

## What gets recorded

Worth knowing up front, because this is a competitive ladder:

- Your Minecraft username and UUID
- Your split times and match results
- A **position timeline** — where you were, roughly once a second,
  during a match. This is used to check that reported splits match
  where you actually were. It is not video, it is about 4KB per run,
  and it is stored privately.

Integrity checks flag runs for human review. They never ban
automatically, and every flag records a plain-English reason so it can
be argued with.

---

## Known rough edges

- Quitting to the title screen without forfeiting leaves the match
  pending until your opponent's client claims it or the abandonment
  sweep voids it

Dying is not a rough edge: it is vanilla on purpose. Deliberate death
resets hunger and returns you to spawn, which is a real technique, so
nothing here penalises it or ends the match on it.
- If nobody else is queuing you will wait indefinitely, with only a
  timer for feedback
- The seed pool is finite; matches fail with an error once it runs out

---

## Repo layout

| Path | What |
|---|---|
| `mod/` | Fabric mod (Java 8 target, Yarn mappings) |
| `backend/` | AWS CDK: Lambda, DynamoDB, S3 |
| `seed-filter/` | cubiomes seed filtering and structure queries |
| `brand/` | logo generator and assets |
| `tools/cubiomes` | vendored submodule |

Clone with `--recurse-submodules`, or run
`git submodule update --init` afterwards.


## Recommended settings

**Brightness: drag the slider to +500%.**

Vanilla caps brightness at 100% (`gamma:1.0`). Speedrun rules
explicitly permit 500%, and effectively every competitive player runs
it, because dark underwater ravines and nether structures are
otherwise very hard to read.

`alt` unlocks the slider so you can just set it: Options → Video
Settings → Brightness, drag to the right. It reads `+500%` at maximum.

Nothing is changed for you. The slider's ceiling is raised and that is
all — `alt` does not touch lighting, shadow rendering, or your setting.
Arriving on vanilla brightness is a real disadvantage against anyone
who knows to change it, so it is worth knowing this is allowed rather
than a grey area.

Fullbright resource packs, or anything pushing past 5.0 or altering
shadow rendering, are NOT permitted.

Editing `options.txt` by hand does not work and never did: Minecraft
clamps gamma back to 1.0 when it saves, so the change disappears the
next time you quit. Raising the slider's own maximum is what makes the
setting stick, which is why the mod does it that way.
