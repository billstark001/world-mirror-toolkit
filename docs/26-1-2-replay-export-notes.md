# 26.1.2 Replay Export Notes

This document captures reusable lessons from porting the Python ReplayMod recovery scripts into the Java toolkit. The verified target is the 26.1.2 client jar and the sample `recording.tmcpr` kept under `TEMP_REPLAY_MOD_EXT`.

## Verified Outcome

The Java exporter can produce dimension-separated Anvil region files from the sample replay:

- Total events read: `237240`
- Chunk packets read: `11323`
- Unique chunks written: `8339`
- Region files written: `23`
- Dimension switches: `5`
- `minecraft:overworld`: `4983` chunks in `16` region files
- `minecraft:the_nether`: `3356` chunks in `7` region files

The expected save layout is:

- Overworld: `<out>/region/*.mca`
- Nether: `<out>/DIM-1/region/*.mca`

If overworld and nether chunks appear mixed in-game, check dimension tracking before investigating palette conversion. In 26.1.2 the useful dimension packets are `ClientboundLoginPacket` and `ClientboundRespawnPacket`.

## Java Versions

The project itself is built with Java 21:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
gradle build
```

The 26.1.2 Minecraft jar uses class file version `69`, so the registry dumper must be compiled and run with Java 25:

```powershell
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat generate-registry-mappings `
  --version-json D:\Programs\world-mirror-toolkit\TEMP_REPLAY_MOD_EXT\version_26.1.2.json `
  --client-jar D:\Programs\world-mirror-toolkit\TEMP_REPLAY_MOD_EXT\client-26.1.2.jar `
  --java-home "C:\Program Files\Java\jdk-25.0.2" `
  --work-dir D:\Programs\world-mirror-toolkit\build\registry-gen-java `
  -o D:\Programs\world-mirror-toolkit\build\generated-registries-26.1.2.json
```

When the wrong JDK is used, `javac` fails with messages like `class file has wrong version 69.0, should be 65.0`.

## Registry Mapping Generation

The exporter needs the same global block state IDs that the network chunk palette uses. These IDs are version-specific and must come from the target Minecraft jar.

The Java implementation is in `world-mirror-schema`:

- `MojangVersionAssets`: resolves Mojang version metadata, downloads the client jar, and downloads libraries.
- `RegistryMappingsGenerator`: compiles and runs a small Java dumper against a named or remapped Minecraft jar.
- `SchemaSkeletonGenerator`: writes a metadata-derived schema skeleton, without packet IDs.

For named or remapped jars, the dumper reads:

- `SharedConstants.getCurrentVersion().dataVersion().version()`
- `Block.BLOCK_STATE_REGISTRY`
- `BuiltInRegistries.BLOCK_ENTITY_TYPE`

For the verified 26.1.2 jar, the generated registry summary is:

- `data_version`: `4790`
- `block_states`: `29873`
- `block_entity_types`: `49`
- `biomes`: `1`

The generated mapping's static `biomes` field is intentionally minimal. The chunk writer now reads the dynamic biome registry from configuration packets and reconstructs each section's real biome palette.

## Obfuscated Jars

Official Mojang metadata can download the official client jar and mappings, but the official client jar may be obfuscated. The current dumper expects named classes such as:

- `net.minecraft.SharedConstants`
- `net.minecraft.core.registries.BuiltInRegistries`
- `net.minecraft.world.level.block.Block`

For obfuscated jars, the recommended implementation path is:

1. Read `downloads.client_mappings` from the Mojang version JSON.
2. Download the client mappings.
3. Remap the official client jar with a tool such as TinyRemapper or SpecialSource.
4. Run the existing registry dumper against the remapped jar.

Avoid maintaining static checked-in registry tables. They are easy to desynchronize from the jar and make palette bugs look like NBT writer bugs.

## Packet Schema Work

Mojang launcher metadata does not include ReplayMod packet IDs. A complete schema generator must inspect Minecraft protocol registration code.

For packet IDs, inspect registration order in:

- `net.minecraft.network.protocol.game.GameProtocols`
- `net.minecraft.network.protocol.configuration.ConfigurationProtocols`

For payload layouts, inspect packet classes and their stream codecs:

- `ClientboundLevelChunkWithLightPacket`
- `ClientboundLevelChunkPacketData`
- `ClientboundLoginPacket`
- `ClientboundRespawnPacket`
- `CommonPlayerSpawnInfo`

For the verified 26.1.2 schema:

- `LEVEL_CHUNK_WITH_LIGHT`: play clientbound packet id `45`
- `LOGIN`: play clientbound packet id `49`
- `RESPAWN`: play clientbound packet id `82`
- `REGISTRY_DATA`: configuration clientbound packet id `7`

Treat packet IDs as state-specific and direction-specific. The same numeric ID can mean different packets in a different state or direction.

## 26.1.2 Packet Layout Lessons

Important details verified against 26.1.2 source:

- `ClientboundLevelChunkPacketData` heightmaps are a stream-codec map, not an NBT blob.
- `LevelChunkSection` reads `short nonEmptyBlockCount`, `short nonEmptyFluidCount`, block paletted container, then biome paletted container.
- `PalettedContainer.read` reads a byte bit width, a palette, then a fixed-size long array. The long array has no length prefix.
- Block palette strategy:
  - `0` bits: single value palette.
  - `1..4` bits: stored as 4-bit local palette on disk.
  - `5..8` bits: local hash palette.
  - `>=9` bits: global palette IDs, repack to a compact disk palette.
- Light data contains four bitset long arrays, then sky update arrays and block update arrays.
- Light section Y is `minSectionY - 1` for the verified 26.1.2 world height.
- `CommonPlayerSpawnInfo` reads a dimension-type holder ID first, then the dimension resource key string.

These details explain why many early NBT files opened as garbage or failed with unknown tags: the issue was usually a wrong packet sub-layout or palette storage assumption, not the binary NBT writer itself.

## Anvil Output Rules

For chunks intended to be readable by Minecraft and external tools:

- Root `DataVersion` should match the target jar, `4790` for 26.1.2.
- Root `xPos`, `yPos`, and `zPos` must be written.
- Root `Status` should be `minecraft:full` for these recovered chunks.
- `sections` should contain real `block_states` palettes.
- `block_entities` must contain absolute `x`, `y`, `z`, and `id`.
- `isLightOn` should be true when light data is attached.
- `structures` should contain empty `starts` and `References` compounds if no structure data is available.
- Keep raw replay bytes under `ReplayRecovered` for auditability.

The Java exporter currently does not generate entities, POI regions, `level.dat`, or heightmaps. It reconstructs biome palettes from the replay registry.

## Memory and Progress

Earlier Python attempts could consume tens of gigabytes because they retained too much decompressed NBT/chunk state at once. The Java exporter keeps only the latest decoded chunk packet per dimension/chunk and then writes grouped region files.

Progress output should include:

- Dimension switches with event index and timestamp.
- Scanned event count, chunk packet count, and unique chunk count.
- Chunk build progress.
- Region file writes with dimension, region file name, and chunk count.

This makes it obvious whether a long run is scanning, building, or writing.

## Validation Commands

Build:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
gradle build :world-mirror-cli:installDist
```

Export:

```powershell
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat export-analysis `
  -i D:\Programs\world-mirror-toolkit\TEMP_REPLAY_MOD_EXT\recording.tmcpr `
  -o D:\Programs\world-mirror-toolkit\build\java-analysis-mca `
  -v 26.1.2 `
  --registry-mappings D:\Programs\world-mirror-toolkit\TEMP_REPLAY_MOD_EXT\generated_mappings\registries_26.1.2.json
```

Count non-empty MCA slots:

```powershell
$files = Get-ChildItem build\java-analysis-mca -Recurse -Filter *.mca
$rows = foreach ($f in $files) {
  $bytes = [IO.File]::ReadAllBytes($f.FullName)
  $count = 0
  for ($i = 0; $i -lt 4096; $i += 4) {
    if (($bytes[$i] -bor $bytes[$i+1] -bor $bytes[$i+2] -bor $bytes[$i+3]) -ne 0) { $count++ }
  }
  [pscustomobject]@{
    Dimension = if ($f.FullName -like '*\DIM-1\*') { 'minecraft:the_nether' } else { 'minecraft:overworld' }
    File = $f.Name
    Chunks = $count
  }
}
$rows | Group-Object Dimension | ForEach-Object {
  [pscustomobject]@{
    Dimension = $_.Name
    RegionFiles = $_.Count
    Chunks = ($_.Group | Measure-Object Chunks -Sum).Sum
  }
}
```

Expected result for the sample:

```text
minecraft:overworld   16 region files   4983 chunks
minecraft:the_nether   7 region files   3356 chunks
```
