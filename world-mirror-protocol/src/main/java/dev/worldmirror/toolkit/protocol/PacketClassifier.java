package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.VarInts;
import dev.worldmirror.toolkit.schema.PacketDescriptor;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import dev.worldmirror.toolkit.schema.PacketState;
import java.util.Optional;

/** Schema-backed packet classifier. */
public final class PacketClassifier {
    private final ProtocolSchema schema;

    public PacketClassifier(ProtocolSchema schema) {
        this.schema = schema;
    }

    public Optional<PacketClassification> classify(byte[] rawPacketPayload, PacketState state) {
        int id = VarInts.leadingValue(rawPacketPayload);
        int length = VarInts.leadingSize(rawPacketPayload);
        return schema.findByPacketId(state, id).map(descriptor -> new PacketClassification(id, length, descriptor));
    }
}
