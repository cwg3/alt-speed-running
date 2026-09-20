# alt

A ranked 1v1 ladder for Minecraft 1.16.1 speedrunning (RSG).

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
- Gravel always drops flint
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

- Dying mid-run has no defined behaviour yet
- Quitting to the title screen without pressing F6 leaves the match
  unresolved
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
