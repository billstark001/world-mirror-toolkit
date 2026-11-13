package dev.worldmirror.toolkit.anvil;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Verifies retained seed provenance before a timestamp replacement. */
final class SeedTerrainEvidence {
    private SeedTerrainEvidence() {}

    static boolean hasReceipt(NbtValue.CompoundValue chunk) {
        if (chunk == null) return false;
        var attachments = chunk.values().get(SeedTerrainDigest.ATTACHMENTS);
        return attachments instanceof NbtValue.CompoundValue fields
                && fields.values().containsKey(SeedTerrainDigest.ATTACHMENT);
    }

    static boolean hasCaptureReceipt(NbtValue.CompoundValue chunk) {
        if (chunk == null) return false;
        var attachments = chunk.values().get(SeedTerrainDigest.ATTACHMENTS);
        return attachments instanceof NbtValue.CompoundValue fields
                && fields.values().containsKey(SeedTerrainDigest.CAPTURE_RECEIPT);
    }

    static boolean canBlendReplay(NbtValue.CompoundValue chunk) {
        var status = chunk.values().get("Status");
        var metadata = chunk.values().get(SeedTerrainDigest.ATTACHMENTS);
        return (NbtValue.stringValue("full").equals(status) || NbtValue.stringValue("minecraft:full").equals(status))
                && (!chunk.values().containsKey(SeedTerrainDigest.BLENDING_DATA)
                        || metadata instanceof NbtValue.CompoundValue attachments
                                && attachments.values().containsKey(SeedTerrainDigest.BLENDING_ATTACHMENT));
    }

    static boolean ownsBlend(NbtValue.CompoundValue chunk, String epoch) {
        return chunk != null && epoch != null
                && chunk.values().get(SeedTerrainDigest.ATTACHMENTS) instanceof NbtValue.CompoundValue attachments
                && NbtValue.stringValue(epoch).equals(attachments.values().get(SeedTerrainDigest.BLENDING_ATTACHMENT));
    }

    static NbtValue.CompoundValue prepareReplay(NbtValue.CompoundValue chunk, String node, long time,
            boolean requireReceipt, String blendEpoch, String seededDimension) {
        var fields = captureFields(chunk);
        if (seededDimension != null) SeededBiomePalette.remap(fields, seededDimension);
        if (blendEpoch != null) {
            var values = attachments(fields);
            values.put(SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue(blendEpoch));
            fields.put(SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(values));
            fields.put(SeedTerrainDigest.BLENDING_DATA, NbtValue.compound(Map.of(
                    "min_section", NbtValue.intValue(SeedTerrainDigest.BLENDING_MIN_SECTION),
                    "max_section", NbtValue.intValue(SeedTerrainDigest.BLENDING_MAX_SECTION))));
        }
        if (!requireReceipt && fields.equals(chunk.values())) return chunk;
        var values = attachments(fields);
        String digest = captureDigest(NbtValue.compound(fields));
        values.put(SeedTerrainDigest.CAPTURE_RECEIPT, NbtValue.compound(Map.of(
                SeedTerrainDigest.CAPTURE_NODE, NbtValue.stringValue(node),
                SeedTerrainDigest.CAPTURE_TIME, NbtValue.longValue(time),
                SeedTerrainDigest.CAPTURE_SOURCE, NbtValue.stringValue(ChunkIndexStore.REPLAY_IMPORT),
                SeedTerrainDigest.CAPTURE_DIGEST, NbtValue.stringValue(digest))));
        fields.put(SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(values));
        return NbtValue.compound(fields);
    }

    private static Map<String, NbtValue> captureFields(NbtValue.CompoundValue chunk) {
        var fields = new LinkedHashMap<>(chunk.values());
        var values = attachments(fields);
        // Replay installs captured terrain. Ownership markers from the input world do not carry over.
        values.remove(SeedTerrainDigest.ATTACHMENT);
        values.remove(SeedTerrainDigest.CAPTURE_RECEIPT);
        if (values.remove(SeedTerrainDigest.BLENDING_ATTACHMENT) != null) fields.remove(SeedTerrainDigest.BLENDING_DATA);
        if (values.isEmpty()) fields.remove(SeedTerrainDigest.ATTACHMENTS);
        else fields.put(SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(values));
        return fields;
    }

    private static Map<String, NbtValue> attachments(Map<String, NbtValue> fields) {
        var tag = fields.get(SeedTerrainDigest.ATTACHMENTS);
        if (tag instanceof NbtValue.CompoundValue compound) return new LinkedHashMap<>(compound.values());
        if (tag != null) throw new IllegalArgumentException("Invalid replay chunk Fabric attachment data");
        return new LinkedHashMap<>();
    }

    static boolean matchesReplayReceipt(NbtValue.CompoundValue chunk, String node, long time,
            NbtValue.CompoundValue expected, String seededDimension) {
        if (!hasCaptureReceipt(chunk) || hasReceipt(chunk)) return false;
        var attachments = (NbtValue.CompoundValue) chunk.values().get(SeedTerrainDigest.ATTACHMENTS);
        if (!(attachments.values().get(SeedTerrainDigest.CAPTURE_RECEIPT) instanceof NbtValue.CompoundValue receipt)
                || !NbtValue.stringValue(node).equals(receipt.values().get(SeedTerrainDigest.CAPTURE_NODE))
                || !NbtValue.longValue(time).equals(receipt.values().get(SeedTerrainDigest.CAPTURE_TIME))
                || !NbtValue.stringValue(ChunkIndexStore.REPLAY_IMPORT).equals(receipt.values().get(SeedTerrainDigest.CAPTURE_SOURCE))
                || !(receipt.values().get(SeedTerrainDigest.CAPTURE_DIGEST) instanceof NbtValue.StringValue proof)) {
            return false;
        }
        try {
            var expectedFields = captureFields(expected);
            if (seededDimension != null) SeededBiomePalette.remap(expectedFields, seededDimension);
            return proof.value().equals(captureDigest(chunk))
                    && captureDigest(NbtValue.compound(captureFields(chunk)))
                            .equals(captureDigest(NbtValue.compound(expectedFields)));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String captureDigest(NbtValue.CompoundValue chunk) {
        return SeedTerrainDigest.captureDigest(compoundFields(chunk));
    }

    static boolean isUnmodified(NbtValue.CompoundValue chunk) {
        if (!hasReceipt(chunk)) return false;
        var attachments = (NbtValue.CompoundValue) chunk.values().get(SeedTerrainDigest.ATTACHMENTS);
        if (!(attachments.values().get(SeedTerrainDigest.ATTACHMENT) instanceof NbtValue.CompoundValue receipt)
                || !NbtValue.intValue(SeedTerrainDigest.SEED_SCHEMA_VERSION).equals(
                        receipt.values().get(SeedTerrainDigest.SEED_SCHEMA))
                || !NbtValue.byteValue(false).equals(receipt.values().get(SeedTerrainDigest.SEED_EDITED))
                || !(receipt.values().get(SeedTerrainDigest.EPOCH) instanceof NbtValue.StringValue epoch)
                || !(receipt.values().get(SeedTerrainDigest.DATA_VERSION) instanceof NbtValue.IntValue version)
                || !(receipt.values().get(SeedTerrainDigest.BASELINE) instanceof NbtValue.StringValue baseline)
                || !(chunk.values().get("DataVersion") instanceof NbtValue.IntValue storedVersion)
                || version.value() <= 0 || version.value() != storedVersion.value()) {
            return false;
        }
        try {
            if (!UUID.fromString(epoch.value()).toString().equals(epoch.value())) return false;
            Map<String, Object> terrain = new LinkedHashMap<>();
            for (String field : new String[] {"sections", "block_entities"}) {
                if (chunk.values().containsKey(field)) terrain.put(field, unpack(chunk.values().get(field)));
            }
            return SeedTerrainDigest.digest(terrain).equals(baseline.value());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static Object unpack(NbtValue value) {
        return switch (value) {
            case NbtValue.ByteValue tag -> tag.value();
            case NbtValue.ShortValue tag -> tag.value();
            case NbtValue.IntValue tag -> tag.value();
            case NbtValue.LongValue tag -> tag.value();
            case NbtValue.FloatValue tag -> tag.value();
            case NbtValue.DoubleValue tag -> tag.value();
            case NbtValue.StringValue tag -> tag.value();
            case NbtValue.ByteArrayValue tag -> tag.value();
            case NbtValue.IntArrayValue tag -> tag.value();
            case NbtValue.LongArrayValue tag -> tag.value();
            case NbtValue.ListValue tag -> tag.values().stream().map(SeedTerrainEvidence::unpack).toList();
            case NbtValue.CompoundValue tag -> compoundFields(tag);
        };
    }

    private static Map<String, Object> compoundFields(NbtValue.CompoundValue compound) {
        Map<String, Object> fields = new LinkedHashMap<>();
        compound.values().forEach((name, nested) -> fields.put(name, unpack(nested)));
        return fields;
    }
}
