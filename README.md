# Expedition

Planetary exploration for Minecraft (NeoForge 1.21.1), built on [ZAS³](https://github.com/west3436/zas-cubed). A spiritual successor to WarpDrive: build a shuttle out of blocks, fly it through a Space dimension, and land on planets whose air, ores, mobs and structures are defined in datapacks.

> **Status: early development.** There is no playable content yet. This repository holds the build, the API jar and the test harness.

## Goals

- **Atmosphere is the terrain.** A planet is defined first by its air: pressure, gas mix and temperature, all simulated by ZAS³.
- **The ship is your base.** Shuttles are player-built block structures with rooms, airlocks and machines.
- **Planets are data.** Everything about a planet lives in a datapack, so pack makers own the worlds.
- **Automation is first-class.** Anything the Shuttle Computer can do, a CC: Tweaked program can do through the Shuttle Interface.
- **Three kinds of drive.** Gas thrusters, electric drives and Bluespace, each changing how a trip plays.

## Requirements

ZAS³ 1.2.0 or newer is the one required mod. Every other integration is optional and loads only when its mod is installed.

## For mod developers

The public API (`expedition-api`) is what other mods build on. It depends only on Minecraft and NeoForge, so a mod can bundle it and still run when Expedition isn't installed.

## Building

`./gradlew build` compiles and checks everything, and `./gradlew runGameTestServer` runs the GameTests on a dedicated server beside the ZAS³ release jar. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

## Licensing

- **Code** is released under the [MIT License](LICENSE). No vgstation13 code (GPLv3/AGPLv3) is copied or translated into this repository; see [CONTRIBUTING.md](CONTRIBUTING.md#vgstation13-code).
- **Art assets** are licensed CC BY-SA 4.0 International under [LICENSE-ASSETS](LICENSE-ASSETS), whether drawn from scratch, derived from vgstation13's sprites (CC BY-SA 3.0 US) or adapted from other sources under compatible licenses. Each asset file's source, license and attribution are listed in [CREDITS](CREDITS). No NonCommercial-licensed assets are used.
