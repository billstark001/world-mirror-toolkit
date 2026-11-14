# Changelog

## [0.1.2] — 2026-11-14

### Added

- Add `import-chunks --world-graph` to supply routing information when the destination's graph is unavailable and `--replay-source` to confirm the world recorded at an endpoint with multiple logical sources.

### Changed

- Require World Mirror metadata schema 2 for mirror imports. Migrate older saves in the mod before importing.
- Validate replay provenance against the destination's upstream root through mirrors and snapshots. `--allow-source-mismatch` can confirm missing evidence. A known mismatch, an invalid lineage, or a cycle blocks the import.
- Preserve the destination's SQLite namespace and recheck its identity under the save lock. For the native graph, verify upstream identities on disk and hold the graph lease while applying the import.
- Use the stored replay and seed source rules for timestamp imports. Replace seed terrain, including all-air chunks, only when it matches its generation receipt and no entity or POI slot protects it.
- Convert void-mirror biome palettes to vanilla equivalents in seeded mirrors. Where enabled, mark imported Overworld borders for blending and preserve existing blending data.
- Use World Mirror Format 0.1.4.

### Fixed

- Keep dry runs from creating or modifying SQLite sidecars in the destination.
- Store a capture receipt in the installed region entry so retrying an interrupted import can finish its SQLite index update.
- Reject oversized or deeply nested NBT and invalid collection lengths before applying an import.
- Refuse imports while destination or native graph recovery is pending, including unfinished local route adoptions. Reject import paths through symbolic links or Windows directory junctions.

## [0.1.1] — 2026-11-13

### Added

- Add `import-chunks --no-backup` for imports into saves that already have a backup.
- Add `import-chunks --replace-unindexed` to replace nonempty mirror chunks without SQLite records and index their replay capture times.

### Changed

- Use World Mirror Format 0.1.3 for scoped source priorities, overwrite decisions, and region verification. Local builds can include a format checkout with Gradle's `--include-build` option.
- Plan mirror imports with region-bounded index queries. Recheck the plan under the save lock and read back the MCA entries before updating SQLite.

### Fixed

- Treat zero-byte MCA placeholders as empty regions and continue to reject truncated headers with data.
- Restore a region after a failed read-back. If an index write fails and the previous indexed claims are still present, restore the corresponding region so the import can be retried.
- Recover verified, already-installed chunks and pending metadata without rewriting their MCA files. Repeating a completed import leaves identical chunks untouched, including all-air chunks.
- Reject aliased dimension paths, invalid region coordinates, and overlapping MCA slots before replacing destination data.
