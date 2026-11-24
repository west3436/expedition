# Contributing to Expedition

Thanks for helping. This guide covers the rule about vgstation13's code, how
assets are credited, how to build and test, and how changes get merged. It
follows [ZAS³](https://github.com/west3436/zas-cubed)'s, which Expedition is
built on.

## vgstation13 code

Expedition is MIT-licensed. vgstation13, whose art it uses, is GPLv3/AGPLv3. To
keep the licenses apart, **no vgstation13 code enters this repository, whether
copied or translated.**

- Expedition's design comes from WarpDrive-style ship movers, not from vg, so
  there is little reason to read vg's code at all. Its shuttle code in
  particular is not a reference: don't port it, paraphrase it or keep its
  structure and names.
- vg's maps (`.dmm`) fall under its code license. Structures, derelicts and
  ruins are designed fresh, never converted.
- The same goes for the wiki, issues and comments: never quote vg code.

Every PR confirms this with the first checkbox in the
[PR template](.github/pull_request_template.md). A reviewer who suspects a
copy asks for a rewrite.

## Using AI

Using AI tools to write code, docs, tests or assets is fully acceptable. The
one condition: **if a PR includes a player-facing change, it must show how a
human tested that change in game.** Player-facing means anything a player
can see, hear or feel, such as blocks, items, ship behaviour, planets, the HUD
or messages.

In the PR template's "Human testing" section, say:

- what you tested, in a dev client, on a dedicated server, or both
- the steps you took and what happened
- evidence: screenshots, a short video or log excerpts

Passing GameTests and unit tests don't replace this for player-facing
changes, though they're still expected. AI-written code follows the same
rules as any other code, the vgstation13 rule included.

## Assets

Code is MIT; assets are CC BY-SA 4.0 International, under
[`LICENSE-ASSETS`](LICENSE-ASSETS). Assets can be drawn from scratch, derived
from vgstation13, or taken from anywhere else whose license allows it. Every
file under a project's `src/*/resources/assets/`, or under the `assets/` of a
built-in resource pack in `src/*/resources/resourcepacks/`, needs an entry in
[`CREDITS`](CREDITS). An entry is a block of `key: value` lines, starting with
the file's path from the repository root and its `origin`; blank lines
separate entries:

- **`origin: original`:** drawn from scratch. Nothing else is needed.
- **`origin: vgstation13`:** `source` (the `.dmi` path in the vg
  repository), `state` (the icon state) and `commit` (the vg commit hash). A
  sound's `source` is its path under vg's `sound/`, with no `state`, and
  `adapted` says how the file was changed. Use only vg's CC BY-SA 3.0 US
  assets, never the CC BY-NC-SA `goon/` folder.
- **`origin: other`:** `source` (a URL, or a description), `license` (an
  SPDX id: `CC0-1.0`, `CC-BY-3.0`, `CC-BY-4.0`, `CC-BY-SA-3.0` or
  `CC-BY-SA-4.0`, the licenses that can be adapted under CC BY-SA 4.0) and
  `attribution` (the author to credit; not needed for `CC0-1.0`).

```
path: expedition/src/main/resources/assets/expedition/textures/block/gas_thruster.png
origin: vgstation13
source: icons/turf/shuttle.dmi
state: example_state
commit: 0123456789ab
```

Leave out any asset whose origin or license is unclear. The full format is
at the top of `CREDITS`. Check it with `./gradlew checkCredits`, which
`./gradlew build` runs too. It runs SS13 Framework's `check_credits.py`, from
the framework's Gradle plugin, so it needs Python 3 (no dependencies):
`python3` or `python` on the `PATH`, or the one `-Pss13.python=<path>` names.
It lists asset files with no entry, entries for files that don't exist,
malformed entries, `goon/` sources and licenses that aren't allowed. CI runs it
in "Build and test".

No NonCommercial or NoDerivatives asset ships in any jar.

Models are Minecraft's own JSON block and item models: boxes on the
1/16-block grid, with textures at 16 pixels per block. Make them in
[Blockbench](https://www.blockbench.net/) or write them by hand. Don't use OBJ
or other free-form meshes. vg's sprites are drawn in a three-quarter view, so
block faces are redrawn from them, while item sprites carry over more
directly. Model files are assets too, so each one needs its own `CREDITS`
entry.

## Building and testing

You need a JDK to run Gradle (17 or newer); the build fetches Java 21 for the
mod itself.

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles, runs the unit tests, and checks the asset credits, formatting, the API's packages and dependencies, its javadoc and its compatibility |
| `./gradlew runGameTestServer` | Boots a dedicated GameTest server with Expedition and the ZAS³ release jar, and runs the GameTests |
| `./gradlew runGameTestServer -Pzas.version=floor` | The same, compiled against and run with the oldest ZAS³ that Expedition supports (`zas_floor_version` in [`gradle.properties`](gradle.properties)). `-Pzas.version=<version>` picks any other ZAS³ release, with `-Pzas.sha256=<hash>` for its jar's hash. The [Adapter tests](https://github.com/west3436/expedition/actions/workflows/compat-adapters.yml) workflow runs the oldest and the newest |
| `./gradlew runGameTestServer -Pcontent=off` | The same, with ZAS³'s content mod switched off in `zas_atmos-startup.toml`: Expedition needs ZAS³'s simulation, not its content, so the game has to start and the tests pass without it. The [Adapter tests](https://github.com/west3436/expedition/actions/workflows/compat-adapters.yml) workflow runs it |
| `./gradlew runGameTestServer -Pcompat.matrix=true` | The same, with ModernFix, FerriteCore and Lithium on the server. The [Compatibility matrix](https://github.com/west3436/expedition/actions/workflows/compat-matrix.yml) workflow runs it nightly |
| `./gradlew runGameTestServer -Pfuzz.seed=<seed>` | The same, with the mover's integrity fuzz on that seed instead of the one every PR runs: the command a fuzz failure prints to replay it. `-Pfuzz.seeds=<n>` runs n seeds from it and `-Pfuzz.moves=<m>` gives each seed m moves. The [Integrity fuzz](https://github.com/west3436/expedition/actions/workflows/integrity-fuzz.yml) workflow runs a thousand seeds nightly |
| `./gradlew spotlessApply` | Formats the Java sources (palantir-java-format) |
| `./gradlew :expedition:runClient` | Starts a dev client with Expedition and ZAS³ |
| `./gradlew :expedition:runServer` | Starts a dev dedicated server with both |
| `./gradlew :expedition-api:apiCompat` | Checks the API for binary-incompatible changes against the last release. Skipped until there is one |
| `./gradlew :expedition-api:javadoc` | Builds the API's javadoc and fails on a broken or missing comment: every public or protected member needs one, with its `@param`, `@return` and `@throws`. `build` runs it |

The build's conventions come from
[SS13 Framework](https://github.com/west3436/ss13-framework)'s Gradle plugins,
from Maven Central at the framework's version, which ZAS³ and NT Engineering
build with too: Java 21, Spotless, the NeoForge and Parchment versions, Maven
Central publishing, the API jar's checks and `checkCredits`. CI calls the
framework's shared workflows. A fix to any of these goes to the framework.

Two projects make up the build. `expedition-api` is the public API: it
depends only on Minecraft and NeoForge, and its jar holds nothing outside
`io.github.west3436.expedition.api`. `expedition` is the mod, which nests the
API and compiles against ZAS³'s API from Maven Central. It also nests
[SS13 Framework](https://github.com/west3436/ss13-framework), mod and API,
from Maven Central at `framework_version` in
[`gradle.properties`](gradle.properties): the framework applies planets'
gravity, which the mod gives it as each dimension's. Mods released together
must nest the same framework minor version while it is 0.x.

ZAS³ itself is not built here. Dev runs and GameTests load the jar attached
to ZAS³'s GitHub release, and the build refuses one whose SHA-256 isn't the
value in [`gradle.properties`](gradle.properties). After a ZAS³ release that
Expedition should build against, set `zas_version` and `zas_sha256` to it.

### Tests

- **API value types and checks** get JUnit 5 tests in `expedition-api`.
  Minecraft's classes are on that classpath, but the game is never
  bootstrapped.
- **Anything in the world** gets a GameTest in the mod's `src/gametest`
  source set, which stays out of the mod jar. SS13 Framework's test kit
  (`io.github.west3436.ss13.testkit`) is on its classpath: `TestPlayers` for
  a player that can be hurt, `ListeningPlayer` for one that keeps what the
  server sends it, and `SoundLog` and `FaultLog` for the sounds played and
  the errors logged.

### Player-facing text

Text a player or operator reads goes in the mod's `en_us.json`, and the code
names its key.

## Pull requests

1. Branch from `main`. Keep a PR to one issue where you can.
2. Fill in the PR template: link the issue (`Closes #nn`) and tick the
   checklist. For a player-facing change made with AI, include the human
   testing evidence described in [Using AI](#using-ai).
3. CI must pass: "ci / Build and test" (asset credits, build, unit tests,
   formatting, the API's javadoc with no comment missing, and GameTests) and
   "ci / Docs links".
4. PRs are squash-merged, so the PR title becomes the commit subject. Write it
   in the imperative: "Add the gas thruster".

## Documentation

Guides for players, server owners, pack makers and mod developers will go on
the [wiki](https://github.com/west3436/expedition/wiki) as the mod gains
content.
