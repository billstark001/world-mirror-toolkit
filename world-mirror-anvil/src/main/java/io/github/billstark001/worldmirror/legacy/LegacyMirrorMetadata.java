package io.github.billstark001.worldmirror.legacy;

import com.fasterxml.jackson.databind.JsonNode;

/** Identifies obsolete schema headers so the importer can require migration. */
public final class LegacyMirrorMetadata {
    private static final int UNVERSIONED_SCHEMA = 0;
    private static final int FIRST_VERSIONED_SCHEMA = 1;

    private LegacyMirrorMetadata() {}

    /** The caller has already verified that the file declares the World Mirror format. */
    public static boolean requiresMigration(JsonNode schema) {
        if (schema.isMissingNode()) return true;
        if (!schema.isIntegralNumber() || !schema.canConvertToInt()) return false;
        int version = schema.intValue();
        return version == UNVERSIONED_SCHEMA || version == FIRST_VERSIONED_SCHEMA;
    }
}
