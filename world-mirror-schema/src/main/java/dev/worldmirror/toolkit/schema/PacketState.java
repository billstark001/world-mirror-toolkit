package dev.worldmirror.toolkit.schema;

/** Minecraft protocol state. Replay files store packets, not state, so the state is schema metadata. */
public enum PacketState {
    CONFIGURATION,
    PLAY
}
