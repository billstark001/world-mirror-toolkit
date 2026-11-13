# world-mirror-toolkit

Java 21 toolkit for recovering Minecraft chunks from ReplayMod recordings into analysis-mode Anvil region files. It reads zipped `.mcpr` files, unpacked ReplayMod directories, or raw `recording.tmcpr` streams with adjacent `metaData.json`.

The exporter selects the Minecraft schema from replay metadata, validates the numeric protocol and DataVersion, decodes configuration and play packets in their own states, and writes version-matched block states, replay registry biomes, light, block entities, and dimension-separated region files. The original chunk and light bytes remain in `ReplayRecovered` for auditing.

See [the compatibility matrix](docs/compatibility.md) for verified versions and limits.

## Build and run

With Java 21 installed, download the CLI ZIP from [GitHub Releases](https://github.com/billstark001/world-mirror-toolkit/releases), extract it, and run `bin/world-mirror-cli` on Linux or macOS or `bin\world-mirror-cli.bat` on Windows.

To build from source with the published Format dependency:

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

Mirror imports preserve nonempty chunks with no matching index record by default. Use `--replace-unindexed` with the mirror timestamp policy to overwrite those chunks and record their replay capture times. The dry run reports them under `unindexed_replaced`. Indexed nonempty chunks still follow their timestamp and source priority. Both modes can fill missing and physically empty chunks, including biome-only chunks. A zero-byte MCA file is an empty region. `--policy empty-only` limits mirror imports to missing and physically empty chunks. `--policy timestamp` is mirror-only. The destination layout defaults from its own `level.dat`. Use `--destination-layout` to override it.

Mirror mode requires metadata schema 2. Open World Mirror to migrate older metadata before importing. Schema 2 uses a stable world ID and retains `sourceId` as the SQLite namespace. Verify that the replay belongs to the intended mirror and pass `--allow-source-mismatch` to confirm the source manually.

Toolkit keeps World Mirror's older on-disk checks in `io.github.billstark001.worldmirror.legacy`. It treats unversioned mirror metadata and zero-byte MCA placeholders as v0. The metadata check directs v0 and v1 saves to the mod for migration.

The command prints a plan before writing. When source and destination Minecraft versions differ, it asks `y/N`. Use `--yes` to accept the mismatch. `--dry-run` only prints the plan. A real import takes the save's `session.lock`, rechecks the plan, and backs up affected region files, mirror metadata, and SQLite before writing. Keep Minecraft closed during import. Each region is reopened and the imported NBT is verified before SQLite is updated. After an interrupted import, `verify_only` counts identical entries that can finish recording their index or metadata without rewriting MCA. `index` writes a JSONL packet-event index. The `schemas` and `selftest` commands report bundled support.

Use `--no-backup` when a backup already exists. The importer keeps a temporary rollback file only for the region it is updating and removes it when that region finishes.

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

The toolkit uses `world-mirror-format:0.1.3` for metadata paths, SQLite, source priority and region verification. Gradle resolves the JAR from the [Format release](https://github.com/billstark001/world-mirror/releases/tag/world-mirror-format-v0.1.3) through Ivy. To build against a neighboring Format checkout, add `--include-build ../world-mirror/world-mirror-format` to the Gradle command.

## Scope

The output is an analysis save, not a fully playable world. It does not reconstruct incremental block updates, entities, POI, or heightmaps. The latest full chunk packet is retained per source, world identity, dimension, and coordinate. A hashed seed can collide across unrelated worlds on the same server, so inspect the export manifest when provenance is uncertain. See [roadmap](docs/roadmap.md) and [schema format](docs/schema.md).
