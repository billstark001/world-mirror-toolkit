package dev.worldmirror.toolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.worldmirror.toolkit.anvil.AnalysisWorldExporter;
import dev.worldmirror.toolkit.anvil.QuerzNbtBridge;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.replay.McprInputResolver;
import dev.worldmirror.toolkit.replay.ReplayIndexWriter;
import dev.worldmirror.toolkit.replay.ReplaySource;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import dev.worldmirror.toolkit.schema.SchemaRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Main CLI entry point. */
@Command(
        name = "world-mirror-toolkit",
        mixinStandardHelpOptions = true,
        version = "world-mirror-toolkit 0.1.0-SNAPSHOT",
        description = "ReplayMod-to-Anvil toolkit for offline world mirroring.",
        subcommands = {
            WorldMirrorCli.IndexCommand.class,
            WorldMirrorCli.ExportAnalysisCommand.class,
            WorldMirrorCli.SchemasCommand.class,
            WorldMirrorCli.SelfTestCommand.class
        })
public final class WorldMirrorCli implements Callable<Integer> {
    public static void main(String[] args) {
        int exit = new CommandLine(new WorldMirrorCli()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "index", description = "Write a JSONL index for a recording.tmcpr/.mcpr input.")
    static final class IndexCommand implements Callable<Integer> {
        @Option(names = {"-i", "--input"}, required = true, description = "recording.tmcpr, unpacked .mcpr directory, or .mcpr zip")
        Path input;

        @Option(names = {"-o", "--out"}, required = true, description = "output JSONL path")
        Path out;

        @Override
        public Integer call() throws Exception {
            try (ReplaySource source = McprInputResolver.open(input)) {
                long count = ReplayIndexWriter.write(source, out);
                System.out.println("indexed " + count + " events -> " + out);
            }
            return 0;
        }
    }

    @Command(name = "export-analysis", description = "Build analysis-mode Anvil .mca files from replay chunk packets.")
    static final class ExportAnalysisCommand implements Callable<Integer> {
        @Option(names = {"-i", "--input"}, required = true, description = "recording.tmcpr, unpacked .mcpr directory, or .mcpr zip")
        Path input;

        @Option(names = {"-o", "--out"}, required = true, description = "output world/dimension directory")
        Path out;

        @Option(names = {"-v", "--version"}, defaultValue = "1.21.7", description = "schema version or alias; use `schemas` to list bundled schemas")
        String version;

        @Option(names = "--schema-file", description = "additional external schema JSON file", split = ",")
        List<Path> schemaFiles = List.of();

        @Option(names = "--dimension", defaultValue = "minecraft:overworld", description = "dimension key to attach recovered chunks to")
        String dimension;

        @Override
        public Integer call() throws Exception {
            ProtocolSchema schema = SchemaRepository.loadBundled(schemaFiles).require(version);
            AnalysisWorldExporter exporter = new AnalysisWorldExporter(schema);
            try (ReplaySource source = McprInputResolver.open(input)) {
                AnalysisWorldExporter.ExportSummary summary = exporter.export(source, out, new DimensionKey(dimension));
                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(mapper.writeValueAsString(summary.asMap()));
            }
            return 0;
        }
    }

    @Command(name = "schemas", description = "List bundled schema versions and high-value packet ids.")
    static final class SchemasCommand implements Callable<Integer> {
        @Option(names = "--schema-file", description = "additional external schema JSON file", split = ",")
        List<Path> schemaFiles = List.of();

        @Override
        public Integer call() throws Exception {
            SchemaRepository repo = SchemaRepository.loadBundled(schemaFiles);
            for (ProtocolSchema schema : repo.list()) {
                System.out.println(schema.minecraftVersion() + " protocol=" + schema.protocolVersion() + " dataVersion=" + schema.dataVersion() + " aliases=" + schema.aliases());
                schema.packets().forEach(packet -> System.out.println("  id=" + packet.id() + " state=" + packet.state() + " kind=" + packet.kind() + " name=" + packet.name() + " parser=" + packet.parser() + " confidence=" + packet.confidence()));
            }
            return 0;
        }
    }

    @Command(name = "selftest", description = "Print runtime integration status.")
    static final class SelfTestCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            QuerzNbtBridge bridge = new QuerzNbtBridge();
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(Map.of(
                    "bundled_schema_count", SchemaRepository.loadBundled().list().size(),
                    "querz_nbt_detected", bridge.isAvailable(),
                    "querz_nbt_api", bridge.detectedApi().orElse("not found; portable fallback writer active")
            )));
            return 0;
        }
    }
}
