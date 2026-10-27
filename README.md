# ZenithProxy StashMover

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that moves cargo between two
endpoints — any distance, any dimension — by chaining ender-pearl static chambers and a
whispered loader bot.

One bot cycles back and forth forever:

- **Input side (pickup)** — take items from the pickup chest, stash them in an ender chest,
  fill the main inventory, then get pulled to the output side.
- **Output side (unload)** — deposit the cargo, then get pulled back to the pickup side.

## How it works

A static chamber is a shaft built around a bubble column with a trapdoor on top. Throw an ender
pearl straight down and it sinks, floats back up and parks itself on the trapdoor. A player
standing next to the chamber can then touch that pearl and get teleported to it.

StashMover uses this in both directions:

1. Walk to the chamber, open its trapdoor, sneak to the centre and throw a pearl straight down.
2. Retreat along the trapdoor's facing side until the pearl has stopped sinking and the bot is
   clear of the shaft.
3. Do the cargo work at that side.
4. Whisper the loader bot on the *other* side (`/w <username> tp`). It touches its own chamber's
   pearl and pulls this bot across.
5. Repeat.

If the pull lands the bot in a neighbouring chamber instead of the configured pit - most sides
have two - it recognises the bubble column it is standing in, climbs out through that chamber's
trapdoor, throws a pearl into it and only then walks over to the configured pit. That keeps
every chamber the loader bot might use loaded.

## Requirements

- Java 21+
- [ZenithProxy](https://github.com/rfresh2/ZenithProxy) on the `java` release channel
  (plugins are not supported on `linux`)
- Two static chambers, one per side, each built around a bubble column with an openable trapdoor.
  A side with two chambers is supported: the bot works out which one it landed in.
- Two loader bots, one standing at each chamber
- Optionally an ender chest and a pearl restock chest near each side

## Installation

1. Download the jar from the [releases page](../../releases).
2. Copy it into the `plugins` folder next to the ZenithProxy launcher.
3. Start ZenithProxy. Plugins are loaded at startup — hot reloading is not supported.

## Setup

All coordinates are optional. Anything left unset is auto-detected at runtime from marker item
frames, which are placed next to the block they label:

| Marker item frame next to | Detects |
|---|---|
| redstone dust | the pickup chest (`inputChest`) |
| redstone block | the unload chest (`outputChest`) |
| ender pearl, with a trapdoor 1–2 blocks below | the pearl pit (`inputPearl` / `outputPearl`) |
| ender pearl, attached to a container | the pearl restock chest (`inputPearlChest` / `outputPearlChest`) |

The two loader bot usernames are the only required setting.

```
/sm inputBot <name>          loader bot standing at the pickup side
/sm outputBot <name>         loader bot standing at the unload side
/sm inputChest <x> <y> <z>
/sm outputChest <x> <y> <z>
/sm inputPearl <x> <y> <z>
/sm outputPearl <x> <y> <z>
/sm inputPearlChest <x> <y> <z>
/sm outputPearlChest <x> <y> <z>
```

Coordinates can be entered by pointing at a block and pressing <kbd>TAB</kbd> in game.
`<key> clear` resets a value back to auto-detection. Changes apply immediately — no restart.

Then start the cycle:

```
/sm start
```

## Commands

| Command | Description |
|---|---|
| `/sm start` / `/sm stop` | start / stop the cycle |
| `/sm status` | show the current state and configuration |
| `/sm debug [on\|off\|info]` | toggle verbose logging / print runtime debug info |
| `/sm restockBelow [count]` | pearl count below which a restock is triggered (default 16) |
| `/sm bulkDelay [ticks]` | delay between each of the 27 slot moves when bulk transferring (0 = all in one tick) |
| `/sm <key> clear` | clear a coordinate and re-enable auto-detection |

## Configuration

Written to `plugins/config/stash-mover.json`.

| Key | Default | Description |
|---|---|---|
| `minPearls` | 8 | pearl count below which a restock is triggered |
| `restockBeforeThrowBelow` | 16 | restock while standing outside the pit once the count drops below this |
| `restockPearlStacks` | 9 | upper bound on stacks per restock; the real count is the free hotbar slots |
| `restockMaxDistance` | 256 | skip the restock if the pearl chest is further away than this |
| `echestSearchRadius` | 16 | radius for finding the nearest ender chest |
| `frameScanRadius` | 32 | radius for scanning marker item frames |
| `frameChestMaxDistance` | 2 | max distance between a marker frame and the block it labels |
| `interactRange` | 4 | interaction range for containers and trapdoors |
| `tpDetectDistance` | 8 | coordinate jump that counts as a successful teleport |
| `teleportSettleTicks` | 60 | ticks to wait after a teleport lands |
| `pathTimeoutTicks` | 1200 | pathfinding timeout |
| `teleportTimeoutTicks` | 600 | teleport wait timeout |
| `bulkTransferDelayTicks` | 0 | ticks between slot moves during a bulk transfer |
| `autoDisableOnEmpty` | true | halt when the pickup chest is found empty |
| `debug` | false | verbose logging |

### Pearl restocking

Restocking happens while the bot is still outside the chamber, because the pearl chest sits a
few blocks away and is only reachable from there — waiting until the pearls run out means finding
out with an empty inventory while already wedged in the shaft. Each restock takes enough stacks to
fill the free hotbar slots and returns anything that does not fit back to the chest, so the main
inventory stays clear for actual cargo.

## Safety notes

- Trapdoors are only ever **right-clicked**, never mined.
- The bot never throws a pearl into a chamber that already holds one, whether that pearl is its
  own or somebody else's.
- After a pull the bot steps out of the shaft *before* doing anything else, and stops if it
  cannot find its way out rather than forcing the trapdoor.
- The module idles completely while the bot is in the queue server.

## Building

```
./gradlew shadowJar
```

The jar is written to `build/libs/`.

## Disclaimer

This plugin automates a game client. Use it only on servers and accounts where automation is
permitted, and at your own risk.

## License

Released under the **GNU Affero General Public License v3.0 or later** — the same license as
ZenithProxy itself, which this plugin links against. See [LICENSE](LICENSE).

If you run a modified version of this plugin as a network service, you must offer its source
code to your users, as required by AGPL-3.0 section 13.