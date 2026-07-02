# world-mirror-toolkit

`world-mirror-toolkit` is a Java/Gradle toolkit for mirroring Minecraft world data out of a ReplayMod recording into local Anvil region files without starting Minecraft.

The current implementation is intentionally conservative: it emits **analysis-mode `.mca` files** containing the latest recovered chunk packet data under a `ReplayRecovered` compound. This is useful for inspection, regression tests, and building the next stage of a fully playable world mirror. It removes the heuristic NBT scanner from the Python draft and routes packet decoding through versioned schema files.

## Goals

- Read `recording.tmcpr`, unpacked `.mcpr` directories, or `.mcpr` zip archives.
- Index ReplayMod packets as JSONL.
- Decode high-value clientbound packets with a versioned schema layer.
- Export valid Anvil region containers for recovered chunks.
- Use Java 21, Gradle, and small modules that can be integrated into a future `world-mirror` mod/toolchain without requiring a Minecraft runtime.
- Depend on the modern ens-gijs/Querz NBT fork for NBT/MCA ecosystem compatibility, while isolating it behind `world-mirror-anvil`.

## Module layout

```text
world-mirror-core      binary cursor, VarInt, coordinates, dimensions, exceptions
world-mirror-schema    versioned packet schema loader and bundled JSON schemas
world-mirror-replay    ReplayMod .tmcpr/.mcpr input and event indexing
world-mirror-protocol  schema-backed packet classification and chunk packet decoders
world-mirror-anvil     analysis chunk builder and Anvil region writer
world-mirror-cli       Picocli command-line application
world-mirror-gui       dummy Swing GUI module for future replacement
```

## Dependencies

The important declared dependencies are in `gradle/libs.versions.toml`:

```toml
ensNbt = "0.1.1"
ensNbtMca = "0.2.0"
```

The toolkit includes the artifacts:

```kotlin
implementation(libs.ensNbt)
implementation(libs.ensNbtMca)
```

The current region writer serializes a minimal internal NBT AST and detects the Querz-compatible package at runtime (`selftest`). This keeps the exported format stable while the fork's MCA API is still evolving. The next intended step is to add a compile-time adapter that delegates chunk/region writes to `nbt-mca`'s streaming writer once its API surface is finalized in your project.

## Build

```bash
gradle :world-mirror-cli:installDist
```

Run the installed CLI:

```bash
./world-mirror-cli/build/install/world-mirror-cli/bin/world-mirror-cli --help
```

Or use Gradle directly:

```bash
gradle :world-mirror-cli:run --args="schemas"
```

## Commands

### List schemas

```bash
gradle :world-mirror-cli:run --args="schemas"
```

Bundled schemas:

- `1.21.7`, alias `26.1.2`: packet IDs confirmed from the original Python tooling.
- `1.20.1-template`: a template that records data version and chunk height but deliberately leaves packet IDs invalid until you fill them in.

### Index a replay

```bash
gradle :world-mirror-cli:run --args="index --input /path/to/recording.tmcpr --out build/events.jsonl"
```

Input may be:

- raw `recording.tmcpr`
- an unpacked `.mcpr` directory containing `recording.tmcpr`
- a zipped `.mcpr` archive

### Export analysis MCA

```bash
gradle :world-mirror-cli:run --args="export-analysis --input /path/to/replay.mcpr --version 1.21.7 --out build/analysis-world"
```

Output layout:

```text
build/analysis-world/
  region/
    r.<rx>.<rz>.mca
```

Each recovered chunk contains a root `ReplayRecovered` compound:

```text
ReplayRecovered.Format
ReplayRecovered.Parser
ReplayRecovered.MinecraftVersion
ReplayRecovered.ProtocolVersion
ReplayRecovered.LatestReplayEvent
ReplayRecovered.LatestTimestampMillis
ReplayRecovered.ChunkPacketCount
ReplayRecovered.BlockEntityCountInPacket
ReplayRecovered.RawLevelChunkData
ReplayRecovered.RawLightData
```

This is not yet a full playable save. The design deliberately keeps raw packet data in a valid Anvil container before doing irreversible palette/entity/block-entity conversion.

### Runtime integration self-test

```bash
gradle :world-mirror-cli:run --args="selftest"
```

This reports bundled schema count and whether a Querz-compatible NBT API package was detected on the runtime classpath.

## External schema files

Pass one or more JSON files:

```bash
gradle :world-mirror-cli:run --args="export-analysis --input replay.mcpr --schema-file ./schemas/my-1.20.4.json --version 1.20.4 --out out"
```

Schema format is documented in [`docs/schema.md`](docs/schema.md).

## Current limitations

- Only `level-chunk-with-light/v1` packet shape is implemented. New protocol shapes should add a parser id instead of modifying this parser in place.
- Dimension detection is not yet decoded from login/respawn; CLI attaches chunks to `minecraft:overworld` by default or to `--dimension`.
- Block-state palette conversion is not yet performed in Java. Raw chunk section bytes are preserved so the conversion can be implemented and regression-tested later.
- Entities, block updates, block entity update packets, registries, heightmaps, biomes, POI, and `level.dat` are not yet reconstructed into a playable vanilla save.

See [`docs/roadmap.md`](docs/roadmap.md) for the recommended next steps.
