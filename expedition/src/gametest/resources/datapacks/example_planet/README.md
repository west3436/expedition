# The example planet

A planet made of data alone: a cold moon with thin methane air, low gravity, its own ores and its own mobs. Copy this folder into a world's `datapacks/` folder, rename the namespace, and change what you like. Expedition's GameTests load it as it is and check every part of it (`MilestoneGateTests`, the M3 gate), so it can't go stale.

JSON has no comments, so each file is explained here. Every planet needs three things:

- a **dimension**
- a **planet file** that makes the dimension a planet
- usually a **biome** of its own

```
example_planet/
  pack.mcmeta
  data/expedition_test/
    dimension/example.json                 the dimension
    worldgen/biome/example_lowlands.json   its one biome
    expedition/planet/example.json         the planet file
```

The namespace here is `expedition_test` because the GameTests read test dimensions from it. Use your own, such as `mypack`.

## `dimension/example.json`: the dimension

An ordinary vanilla dimension file. A dimension made by any generator can be a planet. This one uses Expedition's simple terrain generator, `expedition:planet`, which takes a few dials instead of noise settings.

| Field | Here | What it does |
|---|---|---|
| `type` | `minecraft:overworld` | The dimension type: height, light, day and night. The Overworld's gives a sky and a day–night cycle, which the planet's day and night temperatures follow. |
| `generator.type` | `expedition:planet` | Rolling land from a few dials. |
| `biome_source` | fixed, `expedition_test:example_lowlands` | One biome everywhere. Any vanilla biome source works. |
| `base_block` | stone | The bulk of the ground. Ores replace it. |
| `filler_block`, `filler_depth` | packed ice, 2 | The blocks under the surface. |
| `surface_block` | snow block | The top block. Goats and strays can spawn on it. |
| `floor_block` | bedrock | One layer at the bottom of the world. |
| `ground_level`, `amplitude`, `scale` | 64, 10, 160 | The land's middle height; how far it rises and falls (±10); how wide the hills are. |
| `sea_block` | air | No sea. Leave it out for water up to `sea_level` (63). |

## `worldgen/biome/example_lowlands.json`: the biome

A vanilla biome. It is the planet's own, for two reasons:

- **Features.** Vanilla's Overworld biomes carry vanilla's ores and plants. This one has no `features`, so the only ores underground are the planet file's.
- **Spawns.** Its `spawners` are empty. The planet file's `mobs` replace them anyway (`replace_existing`), but empty spawners make the intent plain.

`temperature` below 0 makes snow rather than rain if precipitation is ever turned on. The colours tint the sky and fog a pale orange.

## `expedition/planet/example.json`: the planet file

This file makes the dimension a planet. Only `dimension` and `space.center` are required, and every other field has a default. `/expedition planet validate` checks the files without a restart, and a wrong file names its field in the server log.

| Field | Here | What it does |
|---|---|---|
| `dimension` | `expedition_test:example` | The dimension this planet is. |
| `space.center`, `space.size` | 20000, 20000; 4096 | Where the planet's square sits in Space, and its side. Squares of two planets may not overlap. |
| `surface.center` | 0, 0 | The column of the dimension under the square's centre. |
| `atmosphere.partial_pressures` | methane 4.0 kPa, nitrogen 1.0 kPa | The open air, handed to ZAS³. Thin: 5 kPa in all, against the Overworld's 101. Gases are ZAS³'s ids. `"vacuum": true` with no pressures makes an airless surface. |
| `atmosphere.temperature` | day 180 K, night 130 K | The open air's temperature by day and night. One number means both. |
| `gravity` | 0.6 | A multiple of the Overworld's. Mobs and players fall slower and jump higher. Falls hurt as much as a fall of 0.6 × the height would on the Overworld. Dropped items and arrows fall slower too. |
| `ores` | iron 30, copper 10, coal 20 | What the crust holds, by weight: iron is placed three times as often as copper. `vein_size` (8 if left out) and a `y` range are optional. Coal here has veins of 12, only from y 0 to 80. |
| `ore_density` | 2.0 | How many veins, as a multiple of the usual 16 a chunk. |
| `mobs.replace_existing` | true | The planet's spawns replace the biome's rather than adding to them. |
| `mobs.monster`, `mobs.creature` | strays; goats | What spawns, by vanilla mob category. Each entry has a `type`, a `weight` and a `group` size range. |
| `structures.deny` | `#minecraft:village` | Structures, or tags of them, that never generate here. `allow` lists the only ones that may. |
| `access.hidden_until_scanned` | false | Whether the planet is hidden on the star map until scanned. |

Ores are placed only in chunks generated after the planet file loads, so add a planet file before anyone explores its dimension.
