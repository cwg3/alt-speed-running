# Installing alt

You need a **paid Minecraft Java account**. The ladder verifies your
identity through Mojang's own session servers, the same mechanism any
vanilla multiplayer server uses — we never see your password or access
token.

Testing is **invite-only** right now. If you have not been invited,
login will refuse with a message saying so.

## 1. Get a launcher that takes modpacks

[Prism Launcher](https://prismlauncher.org) — free, open source,
Windows/Mac/Linux. MultiMC works too.

## 2. Import the pack

Download `alt-<version>.mrpack` from the
[releases page](https://github.com/cwg3/alt-speed-running/releases).

In Prism: **Add Instance → Import → Browse** → pick the `.mrpack`.

That is the whole setup. The pack pins Minecraft 1.16.1, Fabric loader
and Fabric API at the exact versions this build was tested against, so
there is no version to choose and no way to choose the wrong one.

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
