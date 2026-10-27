# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-10-27

### Changed

- The pearl round is now a chain of explicit `GO_*` / `AWAIT_*` state pairs, the same shape the
  villager trader plugin uses: `OPEN_TRAPDOOR → AWAIT_TRAPDOOR_OPEN`, `THROW_PEARL →
  AWAIT_PEARL_SPAWNED → AWAIT_THROW_SETTLE`, `RETREAT → AWAIT_PEARL_STABLE`, `WHISPER →
  AWAIT_TELEPORT`. Each awaited step times out on its own `Timers.tickTimer()`, reset by
  `setState`, instead of sharing one bare tick counter with unrelated branches.
- `THROWING` is now only the pre-flight check (pearl in hand, lid open, drop path clear); the
  actual right-click lives in `THROW_PEARL`, so a rejected click is retried on its own.
- `START` is a pure entry point that clears the per-visit variables; deciding the side and the
  512-block position fallback moved to the new `EVAL_SIDE` state.
- Chamber detection on arrival reads the bubble column the bot is standing in, instead of
  guessing from the distance to the configured pit.
- After climbing out of a chamber the bot now walks to the block just outside the pit on the
  trapdoor's facing side before sneaking in, instead of letting the pathfinder park it on
  whichever side happened to be nearest.

### Fixed

- A side normally has two pearl chambers, but the neighbouring-chamber restock only triggered
  beyond 8 blocks, so a pair sitting 4 blocks apart never got restocked.
- Landing on the rim of a chamber could pick the bot's own column as "the neighbouring chamber".
- Pearl ownership is now explicit in the code: whether a throw is needed counts only our own
  pearls, while the "do not throw into an occupied chamber" check still counts anybody's.

## [1.0.0] - 2026-10-15

First public release.

### Added

- Two-endedpoint cargo cycle driven by ender pearl static chambers: take and stash items at the
  pickup side, deposit them at the unload side, repeat indefinitely across any distance or
  dimension.
- Chamber handling that models what a player does: open every trapdoor sealing the shaft, ride
  the bubble column up, hop out along the trapdoor's facing side, then sneak back in to the
  centre to throw.
- Strict throw-position validation (eye column, height window and an unobstructed drop) so a
  pearl never lands on the rim instead of in the chamber.
- Chamber occupancy check — a pearl is never thrown into a chamber that already holds one,
  whether that pearl belongs to the bot or not.
- Neighbouring-chamber restocking: when a pull lands the bot in the wrong chamber it stocks that
  chamber before heading to the configured one, so the loader bot always has a pearl to work with.
- Proactive pearl restocking from outside the shaft, sized to fill the free hotbar slots, with
  the overflow returned to the chest so the main inventory stays clear for cargo.
- Full auto-detection from marker item frames (redstone dust, redstone block, ender pearl) for
  every coordinate, with manual overrides for all of them.
- Queue-server and post-teleport gates: the module idles completely while the bot is in the
  queue server or when neither stash is reachable, and re-confirms the side before any cargo work
  after a pull.
- Separate retry budgets for throws, trapdoors and chunk-side teleports, so a failing chamber
  cannot reset its own budget.
- Diagnostics: per-state snapshots, chamber 3D dumps, bot surroundings on every stall, and the
  pearl's position while it settles.
- `/sm` command set for configuration, status, debug logging, restock threshold and bulk
  transfer delay.

### Safety

- Trapdoors are only ever right-clicked, never mined.
- The bot refuses to proceed to cargo without a pearl confirmed in the chamber and itself clear
  of the shaft.
- Any situation where the bot cannot find its way out ends in a halt rather than forcing the
  trapdoor.