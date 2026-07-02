package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.schema.PacketDescriptor;

/** Result of mapping a raw packet id through a versioned schema. */
public record PacketClassification(int packetId, int packetIdLength, PacketDescriptor descriptor) {}
