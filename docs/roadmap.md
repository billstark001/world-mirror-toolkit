# Roadmap

## Phase 1: keep parity with the Python draft

- Maintain `.tmcpr` streaming input.
- Maintain JSONL event indexing.
- Decode high-confidence `ClientboundLevelChunkWithLightPacket` data.
- Write analysis `.mca` files.
- Remove byte-offset heuristic scanners.

Status: complete for the 26.1.2 replay sample.

## Phase 2: convert packet chunks into conventional Anvil chunks

- Add block-state registry resources: network global state id -> `{Name, Properties}`.
- Decode `PalettedContainer` sections into disk `sections[].block_states`.
- Decode biome palette and stop writing biome placeholders.
- Write light arrays into the right section tags.
- Generate heightmaps or copy reliable network heightmaps.

Status: block states, light arrays, and replay-sourced biome palettes are implemented for 26.1.2 and 26.2, with 26.3 mappings and schema ready. Heightmaps are not yet written.

## Phase 3: stateful replay merge

- Track world identity, dimension changes, and dimension-type heights from login/respawn and registry packets.
- Merge block update packets after chunk load.
- Merge block entity update packets after chunk load.
- Track entity spawn/metadata/position packets into entity NBT.

Status: world identity, dimension, and dimension-type height tracking are verified for the 26.2 replay; dimension tracking was also verified for 26.1.2. Incremental block/entity merge remains open.

## Phase 4: playable save output

- Expand the minimal versioned `level.dat` into a complete playable save.
- Generate entities and POI regions when data is available.
- Add explicit `analysis` vs `playable` modes.
- Provide a data-fixer boundary: this toolkit should not start Minecraft, but it can optionally prepare resources for an external fixer pipeline.

## Phase 5: integration with world-mirror

- Keep toolkit logic as libraries.
- Expose stable service APIs usable from a mod-side toolchain.
- Share registry/palette/Anvil utility code without requiring the standalone CLI to start Minecraft.
