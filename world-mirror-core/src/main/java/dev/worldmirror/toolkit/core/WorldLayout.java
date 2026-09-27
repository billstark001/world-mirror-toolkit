package dev.worldmirror.toolkit.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** On-disk dimension layout of a Minecraft save. */
public enum WorldLayout {
    LEGACY, NAMESPACED;
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\..*)?$");

    public static WorldLayout forVersion(String version) {
        if (version == null) return LEGACY;
        var match = VERSION.matcher(version);
        if (match.matches()) {
            int major = Integer.parseInt(match.group(1));
            int minor = Integer.parseInt(match.group(2));
            if (major > 26 || major == 26 && minor >= 1) return NAMESPACED;
        }
        return LEGACY;
    }

    public static WorldLayout parse(String value, String version) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "auto" -> forVersion(version);
            case "legacy" -> LEGACY;
            case "namespaced" -> NAMESPACED;
            default -> throw new IllegalArgumentException("unknown layout " + value + "; use auto, legacy, or namespaced");
        };
    }

    public Path dimensionPath(Path worldRoot, DimensionKey dimension) {
        if (this == LEGACY) {
            String subdir = dimension.saveSubdirectory();
            return ".".equals(subdir) ? worldRoot : worldRoot.resolve(subdir);
        }
        String[] parts = dimension.value().split(":", 2);
        if (parts.length != 2 || !parts[0].matches("[a-z0-9_.-]+")
                || !parts[1].matches("[a-z0-9_./-]+") || parts[1].contains("..")) {
            throw new IllegalArgumentException("unsafe dimension key " + dimension.value());
        }
        return worldRoot.resolve("dimensions").resolve(parts[0]).resolve(parts[1]);
    }
}
