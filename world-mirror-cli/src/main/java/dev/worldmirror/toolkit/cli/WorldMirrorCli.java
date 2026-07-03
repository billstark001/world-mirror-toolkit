package dev.worldmirror.toolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.worldmirror.toolkit.anvil.AnalysisWorldExporter;
import dev.worldmirror.toolkit.anvil.QuerzNbtBridge;
import dev.worldmirror.toolkit.anvil.RegistryMappings;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.replay.McprInputResolver;
import dev.worldmirror.toolkit.replay.ReplayIndexWriter;
import dev.worldmirror.toolkit.replay.ReplaySource;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import dev.worldmirror.toolkit.schema.SchemaRepository;
import dev.worldmirror.toolkit.schema.generation.RegistryMappingsGenerator;
import dev.worldmirror.toolkit.schema.generation.SchemaSkeletonGenerator;
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
            WorldMirrorCli.GenerateRegistryMappingsCommand.class,
            WorldMirrorCli.GenerateSchemaCommand.class,
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

        @Option(names = "--registry-mappings", description = "source-derived registry mapping JSON; defaults to TEMP_REPLAY_MOD_EXT/generated_mappings/registries_26.1.2.json when present")
        Path registryMappings;

        @Override
        public Integer call() throws Exception {
            ProtocolSchema schema = SchemaRepository.loadBundled(schemaFiles).require(version);
            RegistryMappings mappings = RegistryMappings.load(resolveRegistryMappings());
            AnalysisWorldExporter exporter = new AnalysisWorldExporter(schema, mappings);
            try (ReplaySource source = McprInputResolver.open(input)) {
                AnalysisWorldExporter.ExportSummary summary = exporter.export(source, out, new DimensionKey(dimension));
                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(mapper.writeValueAsString(summary.asMap()));
            }
            return 0;
        }

        private Path resolveRegistryMappings() {
            if (registryMappings != null) {
                return registryMappings;
            }
            List<Path> candidates = List.of(
                    Path.of("TEMP_REPLAY_MOD_EXT", "generated_mappings", "registries_26.1.2.json"),
                    Path.of("..", "TEMP_REPLAY_MOD_EXT", "generated_mappings", "registries_26.1.2.json"),
                    Path.of("generated_mappings", "registries_26.1.2.json"));
            for (Path candidate : candidates) {
                if (candidate.toFile().isFile()) {
                    return candidate.normalize();
                }
            }
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "missing --registry-mappings; expected 26.1.2 mapping JSON");
        }
    }

    @Command(name = "generate-registry-mappings", description = "Generate registry mapping JSON by running a small dumper against a named Minecraft jar.")
    static final class GenerateRegistryMappingsCommand implements Callable<Integer> {
        @Option(names = {"-v", "--minecraft-version"}, description = "Minecraft/launcher version id; used to resolve Mojang metadata when --version-json is omitted")
        String minecraftVersion;

        @Option(names = "--version-json", description = "local Mojang version JSON; if omitted it is downloaded from Mojang version_manifest_v2")
        Path versionJson;

        @Option(names = "--client-jar", description = "local named/remapped client jar; if omitted the official client jar is downloaded from the version JSON")
        Path clientJar;

        @Option(names = "--work-dir", defaultValue = "generated_mappings/java", description = "download/cache and temporary compilation directory")
        Path workDir;

        @Option(names = {"-o", "--out"}, defaultValue = "generated_mappings/registries.json", description = "output registry mapping JSON")
        Path out;

        @Option(names = "--java-home", description = "JDK used to compile/run the registry dumper; 26.1.2 requires Java 25")
        Path javaHome;

        @Override
        public Integer call() throws Exception {
            RegistryMappingsGenerator generator = new RegistryMappingsGenerator();
            RegistryMappingsGenerator.Result result = generator.generate(new RegistryMappingsGenerator.Options(
                    minecraftVersion, versionJson, clientJar, workDir, out, javaHome));
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(result.asMap()));
            return 0;
        }
    }

    @Command(name = "generate-schema", description = "Generate a metadata-derived schema skeleton; packet ids still require source/bytecode verification.")
    static final class GenerateSchemaCommand implements Callable<Integer> {
        @Option(names = {"-v", "--minecraft-version"}, description = "Minecraft/launcher version id; used to resolve Mojang metadata when --version-json is omitted")
        String minecraftVersion;

        @Option(names = "--protocol-version", description = "protocol/source version label to place in the schema")
        String protocolVersion;

        @Option(names = "--data-version", required = true, description = "DataVersion to place in the schema; registry generation prints this value")
        int dataVersion;

        @Option(names = "--min-section-y", defaultValue = "-4", description = "minimum chunk section y")
        int minSectionY;

        @Option(names = "--max-section-y", defaultValue = "19", description = "maximum chunk section y")
        int maxSectionY;

        @Option(names = "--version-json", description = "local Mojang version JSON; if omitted it is downloaded from Mojang version_manifest_v2")
        Path versionJson;

        @Option(names = "--client-jar", description = "optional client jar path; downloaded if omitted")
        Path clientJar;

        @Option(names = "--work-dir", defaultValue = "generated_schema/java", description = "download/cache directory")
        Path workDir;

        @Option(names = {"-o", "--out"}, required = true, description = "output schema JSON")
        Path out;

        @Override
        public Integer call() throws Exception {
            SchemaSkeletonGenerator generator = new SchemaSkeletonGenerator();
            SchemaSkeletonGenerator.Result result = generator.generate(new SchemaSkeletonGenerator.Options(
                    minecraftVersion, protocolVersion, dataVersion, minSectionY, maxSectionY, versionJson, clientJar, workDir, out));
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(result.asMap()));
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
