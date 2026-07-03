# Versioned schema files

The toolkit never treats a raw packet ID as globally meaningful. A schema maps packet IDs to packet semantics for one protocol/data-version family.

## JSON shape

```json
{
  "schemaVersion": "0.1",
  "minecraftVersion": "1.21.7",
  "protocolVersion": "26.1.2",
  "dataVersion": 4790,
  "minSectionY": -4,
  "maxSectionY": 19,
  "aliases": ["26.1.2"],
  "packets": [
    {
      "state": "play",
      "direction": "clientbound",
      "id": 45,
      "name": "minecraft:level_chunk_with_light",
      "kind": "LEVEL_CHUNK_WITH_LIGHT",
      "parser": "level-chunk-with-light/v1",
      "confidence": "confirmed-by-original-tooling"
    }
  ]
}
```

## Important fields

- `minecraftVersion`: user-facing version selector.
- `protocolVersion`: optional protocol/source version label. It may be a Mojang protocol number, a ReplayMod-era label, or a project-specific alias.
- `dataVersion`: Anvil `DataVersion` to write into chunks.
- `minSectionY` / `maxSectionY`: world height range, used by future palette conversion.
- `packets[].id`: numeric packet ID in that protocol state and direction.
- `packets[].kind`: stable semantic kind used by the toolkit.
- `packets[].parser`: exact decoder implementation to invoke.

## Parser IDs

Implemented now:

- `level-chunk-with-light/v1`: 26.1.2-style `ClientboundLevelChunkWithLightPacket` layout. The parser extracts chunk coordinates, chunk section bytes, block entity NBT, and light data.

Reserved / raw now:

- `raw-registry-data/v1`
- `raw-login/v1`
- `raw-respawn/v1`

When a Minecraft version changes only packet IDs, add a new schema file. When it changes packet layout, add a new parser id and implementation.

## Recommended workflow for adding a version

1. Capture one small ReplayMod recording on the target version.
2. Use `index` to inspect packet ID frequencies.
3. Confirm packet IDs from client protocol registration code, then confirm packet layouts from packet classes and their stream codecs.
4. Copy `schemas/template-1.20.1.json` and fill in real packet IDs.
5. Add a golden sample test that decodes at least one chunk packet.
6. Only mark `confidence` as `confirmed-*` once a sample has been decoded and written.

## What schema generation can and cannot infer

Mojang launcher metadata can reliably provide version JSON, client jar URLs, and library URLs. It cannot directly provide ReplayMod packet IDs or payload layouts.

For packet IDs, inspect the client protocol registration code:

- Play state: `net.minecraft.network.protocol.game.GameProtocols`
- Configuration state: `net.minecraft.network.protocol.configuration.ConfigurationProtocols`

For packet payload layouts, inspect packet classes and stream codecs such as:

- `ClientboundLevelChunkWithLightPacket`
- `ClientboundLevelChunkPacketData`
- `ClientboundLoginPacket`
- `ClientboundRespawnPacket`
- `CommonPlayerSpawnInfo`

For named or already remapped jars, this can be implemented as source/bytecode analysis against stable class names. For official obfuscated jars, first download Mojang client mappings from the version JSON and remap the jar, then run the same analysis on the remapped artifact.
