package dev.worldmirror.toolkit.schema.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.worldmirror.toolkit.core.ToolkitException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generates replay export registry mappings by running a tiny dumper against a named Minecraft jar. */
public final class RegistryMappingsGenerator {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final MojangVersionAssets assets = new MojangVersionAssets();

    public Result generate(Options options) throws IOException, InterruptedException {
        MojangVersionAssets.PreparedAssets prepared = assets.prepare(new MojangVersionAssets.Options(
                options.minecraftVersion(), options.versionJson(), options.clientJar(), options.workDir()));
        Files.createDirectories(options.workDir());
        Files.createDirectories(options.out().getParent());
        List<Path> classpath = new ArrayList<>();
        classpath.add(prepared.clientJar());
        classpath.addAll(prepared.libraries());

        ToolkitException lastFailure = null;
        for (DumperSource source : DumperSource.variants()) {
            try {
                Path sourcePath = options.workDir().resolve("DumpRegistryMappings.java");
                Files.writeString(sourcePath, source.source(), StandardCharsets.UTF_8);
                run(List.of(javaTool(options.javaHome(), "javac"), "-encoding", "UTF-8", "-cp", classpath(classpath), sourcePath.toString()), options.workDir());
                ProcessResult result = run(List.of(javaTool(options.javaHome(), "java"), "-cp", classpath(append(classpath, options.workDir())), "DumpRegistryMappings"), options.workDir());
                JsonNode generated = parseGeneratedJson(result.stdout());
                mapper.writeValue(options.out().toFile(), generated);
                return new Result(
                        options.out(),
                        prepared.versionId(),
                        generated.path("data_version").asInt(),
                        generated.path("block_states").size(),
                        generated.path("block_entity_types").size(),
                        generated.path("biomes").size(),
                        source.name());
            } catch (ToolkitException ex) {
                lastFailure = ex;
            }
        }
        throw new ToolkitException("""
                failed to generate registry mappings. The client jar is likely obfuscated or uses unsupported names.
                For named/remapped jars, pass --client-jar pointing at that jar.
                For official Mojang obfuscated jars, a future implementation should download client mappings and remap the jar before compiling the dumper.
                Last failure: %s""".formatted(lastFailure == null ? "unknown" : lastFailure.getMessage()));
    }

    private JsonNode parseGeneratedJson(String stdout) throws IOException {
        String json = "";
        for (String line : stdout.lines().toList().reversed()) {
            int start = line.indexOf("{\"data_version\"");
            if (start >= 0) {
                json = line.substring(start);
                break;
            }
        }
        if (json.isBlank()) {
            throw new ToolkitException("registry dumper produced no JSON payload");
        }
        return mapper.readTree(json);
    }

    private ProcessResult run(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new ToolkitException("command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
        return new ProcessResult(output);
    }

    private List<Path> append(List<Path> paths, Path path) {
        List<Path> out = new ArrayList<>(paths);
        out.add(path);
        return out;
    }

    private String classpath(List<Path> paths) {
        return String.join(System.getProperty("path.separator"), paths.stream().map(Path::toString).toList());
    }

    private String javaTool(Path javaHome, String executable) {
        if (javaHome == null) {
            return executable;
        }
        String file = System.getProperty("os.name").toLowerCase().contains("win") ? executable + ".exe" : executable;
        return javaHome.resolve("bin").resolve(file).toString();
    }

    public record Options(String minecraftVersion, Path versionJson, Path clientJar, Path workDir, Path out, Path javaHome) {}

    public record Result(Path out, String versionId, int dataVersion, int blockStates, int blockEntityTypes, int biomes, String dumperVariant) {
        public Map<String, Object> asMap() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("out", out.toString());
            outMap.put("version_id", versionId);
            outMap.put("data_version", dataVersion);
            outMap.put("block_states", blockStates);
            outMap.put("block_entity_types", blockEntityTypes);
            outMap.put("biomes", biomes);
            outMap.put("dumper_variant", dumperVariant);
            return outMap;
        }
    }

    private record ProcessResult(String stdout) {}

    private record DumperSource(String name, String source) {
        static List<DumperSource> variants() {
            return List.of(new DumperSource("named-identifier-26.1.2", IDENTIFIER_SOURCE), new DumperSource("named-resource-location", RESOURCE_LOCATION_SOURCE));
        }
    }

    private static final String IDENTIFIER_SOURCE = """
            import java.util.Comparator;
            import java.util.List;
            import net.minecraft.SharedConstants;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.Identifier;
            import net.minecraft.server.Bootstrap;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.block.state.properties.Property;

            public class DumpRegistryMappings {
              private static String q(String s) {
                StringBuilder out = new StringBuilder();
                out.append('"');
                for (int i = 0; i < s.length(); i++) {
                  char c = s.charAt(i);
                  switch (c) {
                    case '\\\\': out.append("\\\\\\\\"); break;
                    case '"': out.append("\\\\\\\""); break;
                    case '\\n': out.append("\\\\n"); break;
                    case '\\r': out.append("\\\\r"); break;
                    case '\\t': out.append("\\\\t"); break;
                    default:
                      if (c < 0x20) out.append(String.format("\\\\u%04x", (int)c));
                      else out.append(c);
                  }
                }
                out.append('"');
                return out.toString();
              }

              private static void dumpSimpleRegistry(StringBuilder out, String name, Registry<?> registry) {
                out.append(q(name)).append(":{");
                boolean first = true;
                for (Object value : registry) {
                  int id = ((Registry)registry).getId(value);
                  Identifier key = ((Registry)registry).getKey(value);
                  if (id < 0 || key == null) continue;
                  if (!first) out.append(',');
                  first = false;
                  out.append(q(Integer.toString(id))).append(':').append(q(key.toString()));
                }
                out.append('}');
              }

              public static void main(String[] args) {
                SharedConstants.tryDetectVersion();
                Bootstrap.bootStrap();
                StringBuilder out = new StringBuilder();
                out.append('{');
                out.append(q("data_version")).append(':').append(SharedConstants.getCurrentVersion().dataVersion().version()).append(',');
                out.append(q("block_states")).append(":{");
                boolean firstState = true;
                for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
                  BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                  if (state == null) continue;
                  if (!firstState) out.append(',');
                  firstState = false;
                  Identifier blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                  out.append(q(Integer.toString(id))).append(":{");
                  out.append(q("Name")).append(':').append(q(blockName.toString()));
                  List<Property.Value<?>> values = state.getValues().sorted(Comparator.comparing(v -> v.property().getName())).toList();
                  if (!values.isEmpty()) {
                    out.append(',').append(q("Properties")).append(":{");
                    boolean firstProp = true;
                    for (Property.Value<?> value : values) {
                      if (!firstProp) out.append(',');
                      firstProp = false;
                      out.append(q(value.property().getName())).append(':').append(q(value.valueName()));
                    }
                    out.append('}');
                  }
                  out.append('}');
                }
                out.append("},");
                dumpSimpleRegistry(out, "block_entity_types", BuiltInRegistries.BLOCK_ENTITY_TYPE);
                out.append(',');
                out.append(q("biomes")).append(":{").append(q("0")).append(':').append(q("minecraft:plains")).append('}');
                out.append('}');
                System.out.println(out.toString());
              }
            }
            """;

    private static final String RESOURCE_LOCATION_SOURCE = IDENTIFIER_SOURCE
            .replace("import net.minecraft.resources.Identifier;", "import net.minecraft.resources.ResourceLocation;")
            .replace("Identifier key", "ResourceLocation key")
            .replace("Identifier blockName", "ResourceLocation blockName");
}
