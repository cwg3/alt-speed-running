# Installing alt

You need a **paid Minecraft Java account**. The ladder verifies your
identity through Mojang's own session servers, the same mechanism any
vanilla multiplayer server uses — we never see your password or access
token.

Testing is **invite-only** right now. If you have not been invited,
login will refuse with a message saying so.

## 1. Pick a launcher

We like [Prism Launcher](https://prismlauncher.org) — free, open
source, Windows/Mac/Linux — because it imports the pack in one step and
keeps this instance separate from your other Minecraft. MultiMC, the
Modrinth App and ATLauncher all work the same way.

Other launchers are fine, **including the official Minecraft
launcher**. It just cannot import a modpack file, so you install the
two mods yourself. Both routes end up with exactly the same setup.

## 2a. Prism, MultiMC, Modrinth App or ATLauncher

Download `alt-<version>.mrpack` from the
[releases page](https://github.com/cwg3/alt-speed-running/releases).

In Prism: **Add Instance → Import → Browse** → pick the `.mrpack`.

That is the whole setup. The pack pins Minecraft 1.16.1, Fabric loader
and Fabric API at the exact versions this build was tested against, so
there is no version to choose and no way to choose the wrong one.

## 2b. The official Minecraft launcher

Slightly more manual, and you must match the versions yourself.

1. Get the [Fabric installer](https://fabricmc.net/use/installer).
   Run it, choose **Minecraft 1.16.1** and **loader 0.19.5**, tick
   *Create profile*, install.
2. From the [releases page](https://github.com/cwg3/alt-speed-running/releases),
   download both jars: `speedrunmcalt-<version>.jar` and
   `fabric-api-0.18.0+build.387-1.16.1.jar`.
3. Drop both into your `mods` folder, creating it if it is not there:
   - Windows `%APPDATA%\.minecraft\mods`
   - macOS `~/Library/Application Support/minecraft/mods`
   - Linux `~/.minecraft/mods`
4. In the launcher, pick the **fabric-loader-1.16.1** profile and play.

Two things to get right, because both fail confusingly:

- **1.16.1 exactly** — not 1.16, not 1.16.5. World generation differs
  between them, which would put you on a different world from your
  opponent.
- **That exact Fabric API build.** Newer ones for "1.16" require
  1.16.2+ internally and the game will refuse to load. If you download
  Fabric API from elsewhere, take the one tagged for 1.16.1.

This installs the mod into your main Minecraft, so it loads every time
you use that profile. If you would rather keep it separate, use a
launcher from 2a.

## 3. Log in and play

Launch the instance, and on the title screen choose **alt**.

Then **Find a match**. You will be paired with another player, shown
your seed type with five seconds to plan, and the race starts when the
countdown hits zero.

## Things worth knowing

**The clock does not start until the world finishes loading.** Loading
is never charged to your run.

**You get told the seed TYPE, not the layout** — village, desert
temple, ruined portal, shipwreck, buried treasure. The five seconds are
for planning your opening, not for memorising a map.

**You will never be given a seed you have played before.** Both players
in a match are guaranteed a seed neither has seen.

**If a seed is broken, vote it unplayable.** That is in the pause menu.
It quarantines the seed for everyone, and it is the main way bad seeds
get found — the filters miss things, and a runner noticing is how we
learn.

**Read [DEVIATIONS.md](DEVIATIONS.md).** A match is not pure vanilla
and we publish exactly how it differs. If you find behaviour that is
not on that list, it is a bug and we want to know.

## When something breaks

Include your Minecraft log (`logs/latest.log` in the instance folder) —
the mod logs every match event with a `[speedrunmcalt]` prefix, which
usually shows what happened.

## Uninstalling

Delete the instance. Nothing is installed outside it.
