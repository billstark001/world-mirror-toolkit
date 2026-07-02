package dev.worldmirror.toolkit.schema;

import java.util.Objects;

/** Versioned packet identity. */
public record PacketDescriptor(
        PacketState state,
        String direction,
        int id,
        String name,
        PacketKind kind,
        String parser,
        String confidence) {
    public PacketDescriptor {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parser, "parser");
        if (confidence == null || confidence.isBlank()) {
            confidence = "medium";
        }
    }
}
