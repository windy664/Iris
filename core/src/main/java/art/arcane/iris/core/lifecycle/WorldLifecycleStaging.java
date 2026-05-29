package art.arcane.iris.core.lifecycle;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldLifecycleStaging {
    private static final Map<String, ChunkGenerator> stagedGenerators = new ConcurrentHashMap<>();
    private static final Map<String, BiomeProvider> stagedBiomeProviders = new ConcurrentHashMap<>();
    private static final Map<String, ChunkGenerator> stagedStemGenerators = new ConcurrentHashMap<>();
    private static final AtomicReference<ChunkGenerator> pendingStemGenerator = new AtomicReference<>();

    private WorldLifecycleStaging() {
    }

    public static void stageGenerator(@NotNull String worldName, @NotNull ChunkGenerator generator, @Nullable BiomeProvider biomeProvider) {
        stagedGenerators.put(worldName, generator);
        if (biomeProvider != null) {
            stagedBiomeProviders.put(worldName, biomeProvider);
        } else {
            stagedBiomeProviders.remove(worldName);
        }
    }

    public static void stageStemGenerator(@NotNull String worldName, @NotNull ChunkGenerator generator) {
        stagedStemGenerators.put(worldName, generator);
        pendingStemGenerator.set(generator);
    }

    @Nullable
    public static ChunkGenerator consumeGenerator(@NotNull String worldName) {
        return stagedGenerators.remove(worldName);
    }

    @Nullable
    public static BiomeProvider consumeBiomeProvider(@NotNull String worldName) {
        return stagedBiomeProviders.remove(worldName);
    }

    @Nullable
    public static ChunkGenerator consumeStemGenerator(@NotNull String worldName) {
        ChunkGenerator generator = stagedStemGenerators.remove(worldName);
        if (generator != null) {
            pendingStemGenerator.compareAndSet(generator, null);
            return generator;
        }
        ChunkGenerator pending = pendingStemGenerator.getAndSet(null);
        if (pending != null) {
            stagedStemGenerators.values().remove(pending);
        }
        return pending;
    }

    public static void clearGenerator(@NotNull String worldName) {
        stagedGenerators.remove(worldName);
        stagedBiomeProviders.remove(worldName);
    }

    public static void clearStem(@NotNull String worldName) {
        ChunkGenerator generator = stagedStemGenerators.remove(worldName);
        if (generator != null) {
            pendingStemGenerator.compareAndSet(generator, null);
        }
    }

    public static void clearAll(@NotNull String worldName) {
        clearGenerator(worldName);
        clearStem(worldName);
    }
}
