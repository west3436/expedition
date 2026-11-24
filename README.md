# Expedition

Planetary exploration for Minecraft (NeoForge 1.21.1), built on [ZAS³](https://github.com/west3436/zas-cubed). A spiritual successor to WarpDrive: build a shuttle out of blocks, fly it through a Space dimension, and land on planets whose air, ores, mobs and structures are defined in datapacks.

> **Status: 0.1.0, the first playable pre-release.** Build a shuttle from a Shuttle Computer, gas thrusters and hull blocks, and fly it through transit between the Overworld, the Moon, the Nether and the End without losing its cabin air. Planets are datapack files. Navigation, automation, the electric and Bluespace drives, and things to find in Space come in later versions. Until 1.0, a new minor version may break the API and the planet file format.

## Goals

- **Atmosphere is the terrain.** A planet is defined first by its air: pressure, gas mix and temperature, all simulated by ZAS³.
- **The ship is your base.** Shuttles are player-built block structures with rooms, airlocks and machines.
- **Planets are data.** Everything about a planet lives in a datapack, so pack makers own the worlds.
- **Automation is first-class.** Anything the Shuttle Computer can do, a CC: Tweaked program can do through the Shuttle Interface.
- **Three kinds of drive.** Gas thrusters, electric drives and Bluespace, each changing how a trip plays.

## Requirements

ZAS³ 1.3.0 or newer is the one required mod. Every other integration is optional and loads only when its mod is installed. [SS13 Framework](https://github.com/west3436/ss13-framework), which applies gravity, is inside Expedition's jar, so it never needs installing.

## For server owners

### Planet borders

A planet can have a world border at the edge of its square, with nothing generated far beyond it. This is off by default. Turn it on in the world's `serverconfig/expedition-server.toml`:

```toml
[planets.border]
    enabled = true
    # Blocks of terrain kept past the border. Chunks wholly beyond are left empty. -1 keeps terrain everywhere.
    voidMargin = 200
    # Planets (dimension ids) left unbounded, such as ["minecraft:overworld"].
    exclude = []
```

- The border is centred on the planet file's `surface.center`, and is `space.size` blocks a side. Each planet's border is its own, and players see the border of the dimension they are in.
- Chunks generated after the option is on are left empty when they lie wholly more than `voidMargin` blocks past the border. Chunks generated before it was on are kept. The server log and `/expedition planet validate` count the region files that lie wholly past the cutoff.
- A ship can't be moved to a spot outside a border.
- The borders apply when the server starts and whenever the config file is reloaded. A planet file changed by `/reload` moves its border at the next start or config reload.

**Pregenerating with Chunky.** [Chunky](https://github.com/pop4959/Chunky) reads each dimension's own border, so a bounded planet can be pregenerated in full. For each planet, run:

```
chunky world <dimension id>
chunky worldborder
chunky radius <half the side + voidMargin>
chunky start
```

`chunky worldborder` takes the border's centre and radius, and the larger `chunky radius` takes in the margin as well. With the defaults, a planet of 8,192 blocks a side needs a radius of 4,296, about 288,000 chunks. A moon of 2,048 blocks a side needs a radius of 1,224, about 23,400 chunks.

## For mod developers

The public API (`expedition-api`) is what other mods build on. It depends only on Minecraft and NeoForge, so a mod can bundle it and still run when Expedition isn't installed.

## Building

`./gradlew build` compiles and checks everything, and `./gradlew runGameTestServer` runs the GameTests on a dedicated server beside the ZAS³ release jar. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

## Licensing

- **Code** is released under the [MIT License](LICENSE). No vgstation13 code (GPLv3/AGPLv3) is copied or translated into this repository; see [CONTRIBUTING.md](CONTRIBUTING.md#vgstation13-code).
- **Art assets** are licensed CC BY-SA 4.0 International under [LICENSE-ASSETS](LICENSE-ASSETS), whether drawn from scratch, derived from vgstation13's sprites (CC BY-SA 3.0 US) or adapted from other sources under compatible licenses. Each asset file's source, license and attribution are listed in [CREDITS](CREDITS). No NonCommercial-licensed assets are used.
