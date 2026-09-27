# world-mirror-toolkit

Java 21 toolkit for recovering Minecraft chunks from ReplayMod recordings into analysis-mode Anvil region files. It reads zipped `.mcpr` files, unpacked ReplayMod directories, or raw `recording.tmcpr` streams with adjacent `metaData.json`.

The exporter selects the Minecraft schema from replay metadata, validates the numeric protocol and DataVersion, decodes configuration and play packets in their own states, and writes version-matched block states, replay registry biomes, light, block entities, and dimension-separated region files. The original chunk and light bytes remain in `ReplayRecovered` for auditing.

See [the compatibility matrix](docs/compatibility.md) for verified versions and limits.

## Build and run

```powershell
gradle :world-mirror-cli:installDist
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat schemas
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat export-analysis -i D:\path\to\replays -o D:\path\to\analysis-world --world longest
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat import-chunks --src D:\path\to\analysis-world --dst D:\path\to\save --mode mirror --dry-run
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat import-chunks --src D:\path\to\analysis-world --dst D:\path\to\save --mode mirror
```

`--version` can be supplied as an explicit assertion. It must match `metaData.json`. `--registry-mappings` can override the bundled, version-specific mapping JSON; its DataVersion must match the schema.

`-i` accepts one or more replay paths or a directory of `.mcpr` files. The exporter matches full chunks across recordings by server identity, login hashed seed, dimension, and coordinates, retaining the latest captured chunk. `--world longest` exports only the world with the longest accumulated play time. The default `--world all` writes one subdirectory per world when there is more than one. A fresh, empty output directory is required.

The layout follows the Minecraft version: 1.21.11 and earlier use `region`, `DIM-1`, and `DIM1`; 26.1 and later use `dimensions/minecraft/{overworld,the_nether,the_end}`. Use `--layout legacy|namespaced` to override it. Each exported world has `level.dat` with the standard DataVersion and Version fields, plus `worldmirror_toolkit_export.json` with source, world identity, layout, capture times, and replay provenance. The minimal `level.dat` is for version detection during import; the export is still an analysis save.

`import-chunks` reads one exported world root and an existing destination save. Its modes are deliberately separate:

| Mode | Destination metadata | Default overwrite policy |
| --- | --- | --- |
| `default` | Reads Minecraft `level.dat`; ignores World Mirror metadata and SQLite even if present | Missing chunks plus existing chunks with no blocks, regardless of biomes |
| `mirror` | Requires supported `worldmirror_meta.json`; reads and updates `data/world_mirror.sqlite` | Missing or physically empty chunks, plus newer replay chunks when an existing index record has comparable time and no stronger source priority |

An existing nonempty region chunk with no matching mirror index record has unknown provenance and is preserved. Both modes always replace physically empty chunks, including biome-only chunks. `--policy empty-only` limits mirror imports to missing and physically empty chunks. `--policy timestamp` is mirror-only. A replay from another server is rejected in mirror mode unless `--allow-source-mismatch` is explicitly given. Unknown mirror metadata schemas are rejected. The destination layout defaults from its own `level.dat`; `--destination-layout` overrides it.

The command prints a plan before writing. When source and destination Minecraft versions differ, it asks `y/N`; `--yes` explicitly accepts the mismatch. `--dry-run` only prints the plan. A real import takes the save's `session.lock` and saves copies of affected region files, mirror metadata, and SQLite under `backups/toolkit-import-<UTC time>` before writing. Keep Minecraft closed during import. `index` writes a JSONL packet-event index; `schemas` and `selftest` report bundled support.

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

The toolkit resolves `world-mirror-format:0.1.1` from the public GitHub Release through a Gradle Ivy repository. A neighboring `world-mirror` checkout is not required. That module owns World Mirror metadata paths, SQLite chunk-index rules, and source priority rules; the toolkit applies them to replay imports. It has no Maven Central dependency. The [format README](https://github.com/billstark001/world-mirror/blob/main/world-mirror-format/README.md) describes its release assets.

## Scope

The output is an analysis save, not a fully playable world. It does not reconstruct incremental block updates, entities, POI, or heightmaps. The latest full chunk packet is retained per source, world identity, dimension, and coordinate. A hashed seed can collide across unrelated worlds on the same server, so inspect the export manifest when provenance is uncertain. See [roadmap](docs/roadmap.md) and [schema format](docs/schema.md).
