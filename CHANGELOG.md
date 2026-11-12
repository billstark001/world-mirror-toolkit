# Changelog

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
