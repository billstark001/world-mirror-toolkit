# Python draft porting notes

Removed or replaced:

- Heuristic NBT scanning is intentionally removed.
- Packet ID constants are moved into schema JSON.
- Replay stream reading is in `world-mirror-replay`.
- Structured chunk extraction is in `world-mirror-protocol`.
- NBT/MCA ownership is isolated in `world-mirror-anvil`.

Ported from the Python draft:

- Registry dump generation for named/remapped jars.
- Block-state palette conversion from network global state IDs to Anvil section palettes.
- Block entity NBT extraction and rewrite with `x`, `y`, `z`, and `id`.
- Chunk light decoding and section `BlockLight` / `SkyLight` attachment.
- Dimension timeline tracking from login/respawn packets.

Still pending from the Python draft:

- Entity/player-state recovery into vanilla entity/player NBT.
- Biome registry reconstruction. Current 26.1.2 export writes a valid `minecraft:plains` placeholder biome palette so chunks remain readable.
- Heightmap reconstruction or reliable heightmap conversion.
- Fully automated packet schema generation from protocol registration code.

The Java code keeps raw chunk section and light bytes in `ReplayRecovered` so future refinements can be audited against the source packet bytes.
