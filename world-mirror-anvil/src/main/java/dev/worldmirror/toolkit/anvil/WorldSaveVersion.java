package dev.worldmirror.toolkit.anvil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Version declared by a Minecraft level.dat, independent of World Mirror metadata. */
public record WorldSaveVersion(String minecraftVersion, int dataVersion) {
    public static WorldSaveVersion read(Path root) throws IOException {
        Path file = root.resolve("level.dat");
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            NbtValue.CompoundValue nbt = new PortableNbtParser().parseDiskRoot(in.readAllBytes());
            if (!(nbt.values().get("Data") instanceof NbtValue.CompoundValue data)) {
                throw new IOException("level.dat has no Data compound: " + file);
            }
            if (!(data.values().get("DataVersion") instanceof NbtValue.IntValue dataVersion)) {
                throw new IOException("level.dat has no DataVersion: " + file);
            }
            String version = "DataVersion-" + dataVersion.value();
            if (data.values().get("Version") instanceof NbtValue.CompoundValue versionTag
                    && versionTag.values().get("Name") instanceof NbtValue.StringValue name) {
                version = name.value();
            }
            return new WorldSaveVersion(version, dataVersion.value());
        } catch (RuntimeException invalid) {
            throw new IOException("invalid level.dat: " + file, invalid);
        }
    }
}
