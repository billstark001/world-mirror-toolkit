package dev.worldmirror.toolkit.anvil;

import java.util.List;
import java.util.Map;

/** Conservative facts needed for overwrite decisions. */
record ChunkFacts(int dataVersion, int x, int z, long captureTimeMillis, boolean noBlocks) {
    static ChunkFacts from(NbtValue.CompoundValue chunk, int headerTimeSeconds) {
        Map<String, NbtValue> root = chunk.values();
        long capture = Integer.toUnsignedLong(headerTimeSeconds) * 1000;
        if (root.get("ReplayRecovered") instanceof NbtValue.CompoundValue replay
                && replay.values().get("CaptureTimestampMillis") instanceof NbtValue.LongValue time) {
            capture = time.value();
        }
        return new ChunkFacts(intValue(root.get("DataVersion")), intValue(root.get("xPos")),
                intValue(root.get("zPos")), capture, noBlocks(root));
    }

    private static int intValue(NbtValue value) {
        return value instanceof NbtValue.IntValue number ? number.value() : -1;
    }

    private static boolean noBlocks(Map<String, NbtValue> root) {
        if (root.get("block_entities") instanceof NbtValue.ListValue blockEntities
                && !blockEntities.values().isEmpty()) return false;
        if (!(root.get("sections") instanceof NbtValue.ListValue sections)) return false;
        for (NbtValue entry : sections.values()) {
            if (!(entry instanceof NbtValue.CompoundValue section)) return false;
            NbtValue states = section.values().get("block_states");
            if (!(states instanceof NbtValue.CompoundValue blockStates)) return false;
            if (!(blockStates.values().get("palette") instanceof NbtValue.ListValue palette)) return false;
            for (NbtValue state : palette.values()) {
                if (!(state instanceof NbtValue.CompoundValue block)) return false;
                if (!(block.values().get("Name") instanceof NbtValue.StringValue name)) return false;
                if (!List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(name.value())) {
                    return false;
                }
            }
        }
        return true;
    }
}
