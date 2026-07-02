package dev.worldmirror.toolkit.anvil;

import java.util.Optional;

/**
 * Runtime capability probe for the ens-gijs/Querz NBT fork.
 *
 * <p>The toolkit keeps its public NBT model independent from the library so the fork can evolve.
 * The Gradle modules still depend on {@code io.github.ens-gijs.nbt:nbt} and {@code nbt-mca}; this
 * bridge records whether the expected Querz-compatible packages are present at runtime. Future
 * work can add a compile-time adapter once the fork's API has settled.</p>
 */
public final class QuerzNbtBridge {
    private final Optional<String> detectedApi;

    public QuerzNbtBridge() {
        detectedApi = detect();
    }

    public Optional<String> detectedApi() {
        return detectedApi;
    }

    public boolean isAvailable() {
        return detectedApi.isPresent();
    }

    private Optional<String> detect() {
        String[] probes = {
                "net.querz.nbt.tag.CompoundTag",
                "io.github.ensgijs.nbt.tag.CompoundTag",
                "io.github.ens_gijs.nbt.tag.CompoundTag"
        };
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String probe : probes) {
            try {
                Class.forName(probe, false, loader);
                return Optional.of(probe.substring(0, probe.length() - ".tag.CompoundTag".length()));
            } catch (ClassNotFoundException ignored) {
                // try next package name
            }
        }
        return Optional.empty();
    }
}
