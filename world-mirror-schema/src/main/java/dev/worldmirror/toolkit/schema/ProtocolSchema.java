package dev.worldmirror.toolkit.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete schema for one Minecraft protocol/data-version family. */
public final class ProtocolSchema {
    private final String schemaVersion;
    private final String minecraftVersion;
    private final String protocolVersion;
    private final int dataVersion;
    private final int minSectionY;
    private final int maxSectionY;
    private final List<String> aliases;
    private final List<PacketDescriptor> packets;

    public ProtocolSchema(
            String schemaVersion,
            String minecraftVersion,
            String protocolVersion,
            int dataVersion,
            int minSectionY,
            int maxSectionY,
            List<String> aliases,
            List<PacketDescriptor> packets) {
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        this.dataVersion = dataVersion;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.aliases = List.copyOf(aliases == null ? List.of() : aliases);
        this.packets = List.copyOf(packets == null ? List.of() : packets);
    }

    public String schemaVersion() { return schemaVersion; }
    public String minecraftVersion() { return minecraftVersion; }
    public String protocolVersion() { return protocolVersion; }
    public int dataVersion() { return dataVersion; }
    public int minSectionY() { return minSectionY; }
    public int maxSectionY() { return maxSectionY; }
    public List<String> aliases() { return aliases; }
    public List<PacketDescriptor> packets() { return packets; }

    public boolean matchesVersion(String requested) {
        return minecraftVersion.equals(requested) || protocolVersion.equals(requested) || aliases.contains(requested);
    }

    public Optional<PacketDescriptor> findByPacketId(int id) {
        return packets.stream().filter(p -> p.id() == id).findFirst();
    }

    public List<PacketDescriptor> findByKind(PacketKind kind) {
        List<PacketDescriptor> out = new ArrayList<>();
        for (PacketDescriptor packet : packets) {
            if (packet.kind() == kind) {
                out.add(packet);
            }
        }
        return out;
    }
}
