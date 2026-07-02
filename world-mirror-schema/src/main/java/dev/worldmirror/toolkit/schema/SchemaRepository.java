package dev.worldmirror.toolkit.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.worldmirror.toolkit.core.ToolkitException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Loads bundled and user-provided versioned schemas. */
public final class SchemaRepository {
    private static final List<String> BUNDLED = List.of(
            "schemas/1.21.7.json",
            "schemas/26.1.2.json",
            "schemas/template-1.20.1.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ProtocolSchema> schemas;

    private SchemaRepository(List<ProtocolSchema> schemas) {
        this.schemas = schemas.stream()
                .sorted(Comparator.comparing(ProtocolSchema::minecraftVersion))
                .toList();
    }

    public static SchemaRepository loadBundled() {
        return loadBundled(List.of());
    }

    public static SchemaRepository loadBundled(List<Path> externalSchemaFiles) {
        ObjectMapper mapper = new ObjectMapper();
        List<ProtocolSchema> loaded = new ArrayList<>();
        ClassLoader cl = SchemaRepository.class.getClassLoader();
        for (String resource : BUNDLED) {
            try (InputStream in = cl.getResourceAsStream(resource)) {
                if (in != null) {
                    loaded.add(mapper.readValue(in, SchemaFile.class).toSchema());
                }
            } catch (IOException ex) {
                throw new ToolkitException("failed to load bundled schema " + resource, ex);
            }
        }
        for (Path file : externalSchemaFiles) {
            try (InputStream in = Files.newInputStream(file)) {
                loaded.add(mapper.readValue(in, SchemaFile.class).toSchema());
            } catch (IOException ex) {
                throw new ToolkitException("failed to load external schema " + file, ex);
            }
        }
        return new SchemaRepository(loaded);
    }

    public List<ProtocolSchema> list() {
        return schemas;
    }

    public ProtocolSchema require(String versionOrAlias) {
        Objects.requireNonNull(versionOrAlias, "versionOrAlias");
        return schemas.stream()
                .filter(schema -> schema.matchesVersion(versionOrAlias))
                .findFirst()
                .orElseThrow(() -> new ToolkitException("unsupported schema/version: " + versionOrAlias + "; available: " + schemas.stream().map(ProtocolSchema::minecraftVersion).toList()));
    }
}
