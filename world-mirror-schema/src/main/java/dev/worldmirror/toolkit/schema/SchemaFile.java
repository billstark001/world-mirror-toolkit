package dev.worldmirror.toolkit.schema;

import java.util.List;

/** Jackson DTO for resources/schema/*.json. */
final class SchemaFile {
    public String schemaVersion;
    public String minecraftVersion;
    public String protocolVersion;
    public int dataVersion;
    public int minSectionY = -4;
    public int maxSectionY = 19;
    public List<String> aliases = List.of();
    public List<PacketFile> packets = List.of();

    ProtocolSchema toSchema() {
        return new ProtocolSchema(
                schemaVersion,
                minecraftVersion,
                protocolVersion,
                dataVersion,
                minSectionY,
                maxSectionY,
                aliases,
                packets.stream().map(PacketFile::toDescriptor).toList());
    }

    static final class PacketFile {
        public String state;
        public String direction = "clientbound";
        public int id;
        public String name;
        public String kind = "UNKNOWN";
        public String parser = "raw";
        public String confidence = "medium";

        PacketDescriptor toDescriptor() {
            return new PacketDescriptor(
                    PacketState.valueOf(state.toUpperCase()),
                    direction,
                    id,
                    name,
                    PacketKind.valueOf(kind.toUpperCase()),
                    parser,
                    confidence);
        }
    }
}
