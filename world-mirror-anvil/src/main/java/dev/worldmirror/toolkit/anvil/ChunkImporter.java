package dev.worldmirror.toolkit.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.worldmirror.toolkit.anvil.ChunkImportPlanner.Decision;

/** Imports analysis chunks into an existing save with explicit vanilla and mirror policies. */
public final class ChunkImporter {
    public enum Mode { DEFAULT, MIRROR }
    public enum Policy { EMPTY_ONLY, TIMESTAMP }

    public record Options(Path source, Path destination, Mode mode, Policy policy, String destinationLayout,
            boolean allowSourceMismatch) {}
    public record Routing(Path worldGraph, String replaySource) {
        public static final Routing AUTO = new Routing(null, null);
    }

    private final ChunkImportPlanner planner = new ChunkImportPlanner();
    private final ChunkImportExecutor executor = new ChunkImportExecutor(planner);

    public record Plan(Options options, AnalysisExportMetadata sourceMetadata,
            WorldSaveVersion destinationVersion, Map<Path, List<Decision>> decisions, int missing,
            int emptyReplaced, int newerReplaced, int preserved, Path database, String sourceId) {
        public Plan {
            Map<Path, List<Decision>> copy = new LinkedHashMap<>();
            decisions.forEach((region, chunks) -> copy.put(region, List.copyOf(chunks)));
            decisions = Collections.unmodifiableMap(copy);
        }
        public int chunksToWrite() {
            return missing + emptyReplaced + newerReplaced + unindexedReplaced();
        }
        public int unindexedReplaced() {
            return (int) decisions.values()
                    .stream()
                    .flatMap(List::stream)
                    .filter(Decision::replaceUnindexed)
                    .count();
        }
        public long chunksToVerifyOnly() {
            return decisions.values().stream().flatMap(List::stream).filter(Decision::verifyOnly).count();
        }
        public boolean versionMismatch() {
            return sourceMetadata.dataVersion() != destinationVersion.dataVersion()
                    || !sourceMetadata.minecraftVersion().equals(destinationVersion.minecraftVersion());
        }
        public Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source_version", sourceMetadata.minecraftVersion());
            value.put("destination_version", destinationVersion.minecraftVersion());
            value.put("mode", options.mode().name().toLowerCase(Locale.ROOT));
            value.put("policy", options.policy().name().toLowerCase(Locale.ROOT));
            if (options.mode() == Mode.MIRROR && !decisions.isEmpty()) {
                var routing = decisions.values().iterator().next().getFirst().routing();
                value.put("replay_source", routing.source());
            }
            value.put("version_mismatch", versionMismatch());
            value.put("missing", missing);
            value.put("empty_replaced", emptyReplaced);
            value.put("newer_replaced", newerReplaced);
            value.put("unindexed_replaced", unindexedReplaced());
            value.put("preserved", preserved);
            value.put("verify_only", chunksToVerifyOnly());
            value.put("regions_to_write",
                    (int) decisions.values()
                            .stream()
                            .filter(chunks -> chunks.stream().anyMatch(chunk -> !chunk.verifyOnly()))
                            .count());
            return value;
        }
    }

    public Plan plan(Options options) throws IOException {
        return plan(options, false);
    }

    public Plan plan(Options options, boolean replaceUnindexed) throws IOException {
        return plan(options, replaceUnindexed, Routing.AUTO);
    }

    public Plan plan(Options options, boolean replaceUnindexed, Routing routing) throws IOException {
        return planner.plan(options, replaceUnindexed, routing, options.destination().toRealPath(), false);
    }

    /** Execute with a retained backup of the affected save files. */
    public Path execute(Plan preview) throws IOException {
        return execute(preview, true);
    }

    /** Execute under the save lock, optionally retaining a backup. */
    public Path execute(Plan preview, boolean keepBackup) throws IOException {
        return executor.execute(preview, keepBackup);
    }
}
