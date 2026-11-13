package io.github.billstark001.worldmirror.legacy;

/** Recognizes the zero-byte region placeholder used by early World Mirror releases. */
public final class LegacyRegionPlaceholder {
    private LegacyRegionPlaceholder() {}

    public static boolean isEmpty(long fileSize) {
        return fileSize == 0;
    }
}
