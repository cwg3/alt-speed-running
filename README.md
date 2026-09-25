<img src="brand/logo_alt_preview.png" alt="alt" width="200">

A 1v1 ladder for Minecraft 1.16.1 speedrunning (RSG).

Two players race the same filtered seed in their own worlds. Whoever
kills the dragon first wins. Ratings move, seasons reset.

**Status: pre-alpha.** It works end to end, but it has had very little
real play. Expect rough edges.

---

## Install

You need Minecraft: Java Edition and the official launcher. Everything
below is free and takes about five minutes.

### 1. Install Fabric Loader for 1.16.1

Download the installer from [fabricmc.net/use](https://fabricmc.net/use/installer/)
and run it:

- **Minecraft Version:** `1.16.1` — untick "Stable Only" if it isn't listed
- **Loader Version:** `0.19.5` or newer
- Click **Install**

This adds a `fabric-loader-1.16.1` profile to your launcher. It does not
touch your existing installations.

### 2. Get the two mod files

You need **both**. Put them in the same folder (step 3).

| File | Where |
|---|---|
| `speedrunmcalt-0.1.0.jar` | from whoever sent you this |
| `fabric-api-0.18.0+build.387-1.16.1.jar` | [Modrinth](https://modrinth.com/mod/fabric-api/version/0.18.0+build.387-1.16.1) |

> **The Fabric API version matters.** Use exactly
> `0.18.0+build.387-1.16.1`. Newer builds that look like they support
> 1.16 (for example `0.42.0+1.16`) actually require 1.16.2 and the game
> will refuse to start with an "Incompatible mods found" screen. If you
> hit that error, this is why.

### 3. Drop them in your mods folder

| OS | Path |
|---|---|
| macOS | `~/Library/Application Support/minecraft/mods` |
| Windows | `%appdata%\.minecraft\mods` |
| Linux | `~/.minecraft/mods` |

Create the `mods` folder if it isn't there.

### 4. Launch

Open the Minecraft launcher, select the **`fabric-loader-1.16.1`**
installation, and hit Play.

You should see an **`alt - speed-running`** button at the top of the
title screen.

---

## Playing

1. Click **alt - speed-running** on the title screen.
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
confirmation first. Forfeiting counts as a loss.

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
