package dev.worldmirror.toolkit.schema;

/** Semantic packet categories understood by the toolkit. */
public enum PacketKind {
    LEVEL_CHUNK_WITH_LIGHT,
    BLOCK_ENTITY_DATA,
    REGISTRY_DATA,
    FINISH_CONFIGURATION,
    START_CONFIGURATION,
    LOGIN,
    RESPAWN,
    ENTITY_RAW,
    PLAYER_STATE_RAW,
    UNKNOWN
}
