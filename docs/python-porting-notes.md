# Python draft porting notes

Removed or replaced:

- Heuristic NBT scanning is intentionally removed.
- Packet ID constants are moved into schema JSON.
- Replay stream reading is in `world-mirror-replay`.
- Structured chunk extraction is in `world-mirror-protocol`.
- NBT/MCA ownership is isolated in `world-mirror-anvil`.

Still pending from the Python draft:

- Registry dump generation.
- Block-state palette conversion.
- Block entity NBT extraction and rewrite.
- Entity/player-state recovery.
- Full dimension timeline.

The Java code keeps the raw chunk section and light bytes in `ReplayRecovered` so those pending features can be added without losing data.
