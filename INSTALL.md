# Installing alt

You need a **paid Minecraft Java account**. The ladder verifies your
identity through Mojang's own session servers, the same mechanism any
vanilla multiplayer server uses — we never see your password or access
token.

Testing is **invite-only** right now. If you have not been invited,
login will refuse with a message saying so — so sort that out before
installing anything: [open an issue](https://github.com/cwg3/alt-speed-running/issues/new)
with the Minecraft username you want to play on.

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
   Run it, choose **Minecraft 1.16.1** and **loader 0.19.5** (or newer),
   tick *Create profile*, install. If 1.16.1 is not in the version list,
   untick **Stable Only** — the installer hides old releases by default.

   This adds a `fabric-loader-1.16.1` profile. It does not touch your
   existing installations.
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
- **That exact Fabric API build.** Newer ones that look like they
  support 1.16 — `0.42.0+1.16`, for example — actually require 1.16.2
  internally, and the game refuses to start with an **"Incompatible
  mods found"** screen. If you hit that error, this is why. Taking the
  jar from the releases page avoids it; if you get Fabric API
  elsewhere, take the one tagged for 1.16.1.

This installs the mod into your main Minecraft, so it loads every time
you use that profile. If you would rather keep it separate, use a
launcher from 2a.

## 3. Log in and play

Launch the instance. At the top of the title screen there is a new
button reading **alt  speed-running** — click it.

Then **Find a match**. You will be paired with another player, shown
your seed type with ten seconds to plan, and the race starts when the
countdown hits zero.

## Things worth knowing

**You can keep playing while you queue.** Hit Find Match, back out to
the title screen, and load a practice world - the search keeps running.
When an opponent is found you will hear the level-up chime, your
practice world saves and closes on its own, and the match world loads.
No need to sit on the menu watching a timer.

**The clock does not start until the world finishes loading.** Loading
is never charged to your run.

**You get told the seed TYPE, not the layout** — village, desert
temple, ruined portal, shipwreck, buried treasure. The ten seconds are
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

**Note — "Worlds using Experimental Settings are not supported".**
Minecraft shows this if you open an old match world from the
Singleplayer list. It is expected and safe to click through.

A match world gives the Nether its own seed, separate from the
Overworld's, so both players get an identical Nether without either of
them being able to infer it from the Overworld. That is a custom
dimension setup, and Minecraft flags any world using one — it has no
way to tell a deliberate one from an experimental snapshot feature.

You will never see it during a match: the world is created and entered
directly, so the prompt has nowhere to appear. It only shows up when
you go back to a finished match world by hand. Nothing is wrong with
the world, and the warning is technically accurate, which is why we
have not silenced it.

## If Java crashes when you QUIT (macOS)

You may see a crash dialog after closing the game, with
`libjemalloc.dylib` in it. Your world is already saved by then - the
crash happens after shutdown, not during play, so nothing is lost.

It is not this mod. It is LWJGL's bundled memory allocator segfaulting
under the launcher's old Java 8 runtime on macOS, and it predates this
project on machines that have it.

The mod already sets `org.lwjgl.system.allocator=system` before the
game starts, which avoids it. If you still see it:

- **Run on a newer Java.** 1.16.1 with Fabric is fine on Java 17, and
  the crash is specific to the Java 8 runtime the official launcher
  ships. In Prism: Edit Instance -> Settings -> Java.
- Or add `-Dorg.lwjgl.system.allocator=system` to your JVM arguments
  yourself, in the same place.

## When something breaks

Include your Minecraft log (`logs/latest.log` in the instance folder) —
the mod logs every match event with a `[speedrunmcalt]` prefix, which
usually shows what happened.

## Uninstalling

Delete the instance. Nothing is installed outside it.
