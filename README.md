# world-mirror-toolkit

Java 21 toolkit for recovering Minecraft chunks from ReplayMod recordings into analysis-mode Anvil region files. It reads zipped `.mcpr` files, unpacked ReplayMod directories, or raw `recording.tmcpr` streams with adjacent `metaData.json`.

The exporter selects the Minecraft schema from replay metadata, validates the numeric protocol and DataVersion, decodes configuration and play packets in their own states, and writes version-matched block states, replay registry biomes, light, block entities, and dimension-separated region files. The original chunk and light bytes remain in `ReplayRecovered` for auditing.

See [the compatibility matrix](docs/compatibility.md) for verified versions and limits.

## Build and run

```powershell
gradle :world-mirror-cli:installDist
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat schemas
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat export-analysis -i D:\path\to\replay.mcpr -o D:\path\to\analysis-world
```

`--version` can be supplied as an explicit assertion. It must match `metaData.json`. `--registry-mappings` can override the bundled, version-specific mapping JSON; its DataVersion must match the schema.

For a replay containing one world, the output layout is `<out>/region/*.mca` for the overworld, `<out>/DIM-1/region/*.mca` for the Nether, and `<out>/DIM1/region/*.mca` for the End. When the replay contains multiple worlds, each gets its own `<out>/world-<hashed-seed>/` root with that layout. A fresh, empty output directory is required so old region files cannot contaminate an export. `index` writes a JSONL packet-event index. `schemas` lists bundled protocol schemas. `selftest` reports runtime integration status.

## Modules

| Module | Role |
| --- | --- |
| `world-mirror-core` | Binary primitives and shared World Mirror path adapter |
| `world-mirror-schema` | Versioned packet schemas and mapping generator |
| `world-mirror-replay` | ReplayMod input and metadata |
| `world-mirror-protocol` | Stateful packet and chunk decoding |
| `world-mirror-anvil` | Analysis chunks and region writer |
| `world-mirror-cli` | Command line interface |
| `world-mirror-gui` | Placeholder UI |

The toolkit resolves `world-mirror-format:0.1.1` from the public GitHub Release through a Gradle Ivy repository. A neighboring `world-mirror` checkout is not required. That module owns World Mirror metadata paths, SQLite chunk-index rules, and overwrite decisions; it has no Maven Central dependency. The [format README](https://github.com/billstark001/world-mirror/blob/main/world-mirror-format/README.md) describes its release assets.

## Scope

The output is an analysis save, not a fully playable world. It does not reconstruct incremental block updates, entities, POI, heightmaps, or `level.dat`. The latest full chunk packet is retained per world, dimension, and coordinate. The world identifier comes from the login/respawn hashed seed; recordings from unrelated worlds with an identical hashed seed still need manual separation. See [roadmap](docs/roadmap.md) and [schema format](docs/schema.md).
