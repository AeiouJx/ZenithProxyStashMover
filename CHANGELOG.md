# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

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