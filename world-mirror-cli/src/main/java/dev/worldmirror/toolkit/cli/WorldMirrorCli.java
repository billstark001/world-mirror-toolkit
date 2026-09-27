package dev.worldmirror.toolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.worldmirror.toolkit.anvil.AnalysisWorldExporter;
import dev.worldmirror.toolkit.anvil.ChunkImporter;
import dev.worldmirror.toolkit.anvil.QuerzNbtBridge;
import dev.worldmirror.toolkit.anvil.RegistryMappings;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.WorldLayout;
import dev.worldmirror.toolkit.replay.McprInputResolver;
import dev.worldmirror.toolkit.replay.ReplayIndexWriter;
import dev.worldmirror.toolkit.replay.ReplaySource;
import dev.worldmirror.toolkit.replay.ReplayMetadata;
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
            WorldMirrorCli.ImportChunksCommand.class,
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
        @Option(names = {"-i", "--input"}, required = true, arity = "1..*",
                description = "one or more .mcpr files, a folder of .mcpr files, or an unpacked replay")
        List<Path> inputs;

        @Option(names = {"-o", "--out"}, required = true, description = "output world/dimension directory")
        Path out;

        @Option(names = {"-v", "--version"}, description = "schema version or alias; defaults to replay metadata")
        String version;

        @Option(names = "--schema-file", description = "additional external schema JSON file", split = ",")
        List<Path> schemaFiles = List.of();

        @Option(names = "--dimension", defaultValue = "minecraft:overworld", description = "dimension key to attach recovered chunks to")
        String dimension;

        @Option(names = "--registry-mappings", description = "override bundled, version-matched registry mapping JSON")
        Path registryMappings;

        @Option(names = "--layout", defaultValue = "auto", description = "auto, legacy, or namespaced")
        String layout;

        @Option(names = "--world", defaultValue = "all", description = "all, longest, or a world id from a previous scan")
        String world;

        @Override
        public Integer call() throws Exception {
            List<Path> replayInputs = McprInputResolver.resolveInputs(inputs);
            ReplayMetadata metadata = McprInputResolver.readMetadata(replayInputs.getFirst());
            if (metadata.fileFormatVersion() != 14) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "unsupported ReplayMod file format " + metadata.fileFormatVersion() + "; expected 14");
            }
            if (version != null && !SchemaRepository.loadBundled(schemaFiles).require(version).minecraftVersion().equals(metadata.minecraftVersion())) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "--version " + version + " conflicts with replay metadata " + metadata.minecraftVersion());
            }
            ProtocolSchema schema = SchemaRepository.loadBundled(schemaFiles).require(metadata.minecraftVersion(), metadata.protocol());
            RegistryMappings mappings = registryMappings == null
                    ? RegistryMappings.loadBundled(schema.minecraftVersion()) : RegistryMappings.load(registryMappings);
            if (mappings.dataVersion() != schema.dataVersion()) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "registry mapping DataVersion " + mappings.dataVersion() + " does not match " + schema.minecraftVersion()
                                + " DataVersion " + schema.dataVersion());
            }
            AnalysisWorldExporter exporter = new AnalysisWorldExporter(schema, mappings);
            AnalysisWorldExporter.ExportSummary summary = exporter.export(replayInputs, out,
                    new DimensionKey(dimension), WorldLayout.parse(layout, schema.minecraftVersion()), world);
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(summary.asMap()));
            return 0;
        }

    }

    @Command(name = "import-chunks", description = "Import analysis chunks into an existing save.")
    static final class ImportChunksCommand implements Callable<Integer> {
        @Option(names = "--src", required = true, description = "one exported world root")
        Path source;

        @Option(names = "--dst", required = true, description = "existing destination save root")
        Path destination;

        @Option(names = "--mode", defaultValue = "default", description = "default or mirror")
        String mode;

        @Option(names = "--policy", defaultValue = "auto", description = "auto, empty-only, or timestamp")
        String policy;

        @Option(names = "--destination-layout", defaultValue = "auto",
                description = "auto, legacy, or namespaced")
        String destinationLayout;

        @Option(names = "--allow-source-mismatch", description = "permit a replay source different from mirror sourceId")
        boolean allowSourceMismatch;

        @Option(names = "--dry-run", description = "plan changes without modifying the destination")
        boolean dryRun;

        @Option(names = "--yes", description = "accept a source/destination version mismatch")
        boolean yes;

        @Override
        public Integer call() throws Exception {
            ChunkImporter.Mode selectedMode = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
                case "default" -> ChunkImporter.Mode.DEFAULT;
                case "mirror" -> ChunkImporter.Mode.MIRROR;
                default -> throw new CommandLine.ParameterException(new CommandLine(this), "unknown mode: " + mode);
            };
            ChunkImporter.Policy selectedPolicy = switch (policy.toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> selectedMode == ChunkImporter.Mode.MIRROR
                        ? ChunkImporter.Policy.TIMESTAMP : ChunkImporter.Policy.EMPTY_ONLY;
                case "empty-only" -> ChunkImporter.Policy.EMPTY_ONLY;
                case "timestamp" -> ChunkImporter.Policy.TIMESTAMP;
                default -> throw new CommandLine.ParameterException(new CommandLine(this), "unknown policy: " + policy);
            };
            ChunkImporter importer = new ChunkImporter();
            var plan = importer.plan(new ChunkImporter.Options(source, destination, selectedMode,
                    selectedPolicy, destinationLayout, allowSourceMismatch));
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(plan.asMap()));
            if (dryRun) return 0;
            if (plan.versionMismatch() && !yes) {
                System.out.print("Minecraft/DataVersion differs; import chunks anyway? [y/N] ");
                System.out.flush();
                int answer = System.in.read();
                if (answer != 'y' && answer != 'Y') return 2;
            }
            Path backup = importer.execute(plan);
            System.out.println("import complete; backup=" + (backup == null ? "none (no changes)" : backup));
            return 0;
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

        @Option(names = "--extra-jar", description = "additional jars needed by a Fabric-patched named Minecraft jar")
        List<Path> extraJars = List.of();

        @Option(names = "--fabric-classpath-file", description = "Gradle runtime classpath listing; adds Fabric API/loader jars for a patched named jar")
        Path fabricClasspathFile;

        @Override
        public Integer call() throws Exception {
            RegistryMappingsGenerator generator = new RegistryMappingsGenerator();
            RegistryMappingsGenerator.Result result = generator.generate(new RegistryMappingsGenerator.Options(
                    minecraftVersion, versionJson, clientJar, workDir, out, javaHome, extraJars, fabricClasspathFile));
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

        @Option(names = "--network-protocol", required = true, description = "numeric Minecraft network protocol from the target client")
        int networkProtocol;

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
                    minecraftVersion, protocolVersion, networkProtocol, dataVersion, minSectionY, maxSectionY, versionJson, clientJar, workDir, out));
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
