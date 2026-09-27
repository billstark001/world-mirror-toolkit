package dev.worldmirror.toolkit.replay;

/** Session and version fields from ReplayMod metaData.json. */
public record ReplayMetadata(String minecraftVersion, int protocol, int fileFormatVersion,
        long startTimeMillis, long durationMillis, String serverName, boolean singleplayer) {
    public String sourceKey() {
        return (singleplayer ? "singleplayer:" : "server:")
                + (serverName == null ? "unknown" : serverName.strip().toLowerCase(java.util.Locale.ROOT));
    }
}
