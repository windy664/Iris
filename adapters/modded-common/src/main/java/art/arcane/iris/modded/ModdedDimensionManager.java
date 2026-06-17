/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.engine.framework.Engine;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class ModdedDimensionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, Handle> HANDLES = new ConcurrentHashMap<>();
    private static volatile ModdedServerAccess access;

    private ModdedDimensionManager() {
    }

    public static void bindAccess(ModdedServerAccess serverAccess) {
        access = serverAccess;
    }

    public static Handle handle(String dimensionId) {
        return HANDLES.get(dimensionId);
    }

    public static List<Handle> handles() {
        return new ArrayList<>(HANDLES.values());
    }

    public static ServerLevel level(MinecraftServer server, String dimensionId) {
        Handle handle = HANDLES.get(dimensionId);
        if (handle != null && handle.level().getServer() == server) {
            return handle.level();
        }
        ResourceKey<Level> key = levelKey(dimensionId);
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().equals(key)) {
                return level;
            }
        }
        return null;
    }

    public static Engine engine(MinecraftServer server, String dimensionId) {
        ServerLevel level = level(server, dimensionId);
        if (level == null) {
            return null;
        }
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator)) {
            return null;
        }
        return generator.commandEngine();
    }

    public static Handle create(MinecraftServer server, String dimensionId, String packKey, long seed) {
        ModdedServerAccess serverAccess = requireAccess();
        synchronized (LOCK) {
            ResourceKey<Level> key = levelKey(dimensionId);
            Handle existing = HANDLES.get(dimensionId);
            if (existing != null && serverAccess.hasLevel(server, key)) {
                existing.generator().repoint(packKey, seed);
                return existing;
            }
            if (serverAccess.hasLevel(server, key)) {
                ServerLevel present = level(server, dimensionId);
                if (present == null || !(present.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator)) {
                    throw new IllegalStateException("Iris cannot inject dimension '" + dimensionId + "': a non-Iris level with that id is already loaded");
                }
                LOGGER.warn("Iris dimension '{}' is already present in the running server; reusing it", dimensionId);
                generator.repoint(packKey, seed);
                Handle handle = new Handle(dimensionId, packKey, seed, present, generator);
                HANDLES.put(dimensionId, handle);
                return handle;
            }

            try {
                Handle handle = inject(server, serverAccess, dimensionId, key, packKey, seed);
                HANDLES.put(dimensionId, handle);
                LOGGER.info("Iris injected runtime dimension '{}' (pack={} seed={})", dimensionId, packKey, seed);
                return handle;
            } catch (Throwable e) {
                LOGGER.error("Iris failed to inject runtime dimension '{}' (pack={} seed={})", dimensionId, packKey, seed, e);
                throw new IllegalStateException("Iris runtime dimension injection failed for " + dimensionId, e);
            }
        }
    }

    public static Handle createPersistent(MinecraftServer server, String dimensionId, String packKey, long seed) {
        Handle handle = create(server, dimensionId, packKey, seed);
        ModdedDimensionRegistryStore.put(server, new ModdedDimensionRegistryStore.PersistentDimension(dimensionId, packKey, seed));
        return handle;
    }

    public static boolean removePersistent(MinecraftServer server, String dimensionId) {
        boolean removed = remove(server, dimensionId);
        ModdedDimensionRegistryStore.remove(server, dimensionId);
        return removed;
    }

    public static boolean remove(MinecraftServer server, String dimensionId) {
        return remove(server, dimensionId, false);
    }

    public static boolean remove(MinecraftServer server, String dimensionId, boolean wipeStorage) {
        ModdedServerAccess serverAccess = requireAccess();
        synchronized (LOCK) {
            ResourceKey<Level> key = levelKey(dimensionId);
            ServerLevel level = level(server, dimensionId);
            if (level == null) {
                HANDLES.remove(dimensionId);
                return false;
            }
            try {
                evacuate(server, level);
                if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
                    generator.unbindEngine();
                }
                ModdedWorldEngines.evict(level);
                level.save(null, true, false);
                serverAccess.removeLevel(server, key);
                level.close();
                HANDLES.remove(dimensionId);
                if (wipeStorage) {
                    ModdedDimensionStorage.wipe(server, key);
                }
                LOGGER.info("Iris removed runtime dimension '{}'", dimensionId);
                return true;
            } catch (Throwable e) {
                LOGGER.error("Iris failed to remove runtime dimension '{}'", dimensionId, e);
                throw new IllegalStateException("Iris runtime dimension removal failed for " + dimensionId, e);
            }
        }
    }

    public static boolean teleport(ServerPlayer player, MinecraftServer server, String dimensionId, double x, double y, double z) {
        ServerLevel level = level(server, dimensionId);
        if (level == null) {
            return false;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        double targetY = y;
        if (y == Double.MIN_VALUE) {
            targetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        }
        player.teleportTo(level, x, targetY, z, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        return true;
    }

    private static Holder<DimensionType> resolveDimensionType(RegistryAccess registryAccess) {
        Registry<DimensionType> registry = registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<DimensionType> studioPool = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("irisworldgen:studio_pool"));
        return registry.get(studioPool)
                .map(reference -> (Holder<DimensionType>) reference)
                .orElseGet(() -> registry.getOrThrow(BuiltinDimensionTypes.OVERWORLD));
    }

    private static Handle inject(MinecraftServer server, ModdedServerAccess serverAccess, String dimensionId, ResourceKey<Level> key, String packKey, long seed) {
        RegistryAccess registryAccess = server.registryAccess();
        Holder<DimensionType> dimensionType = resolveDimensionType(registryAccess);
        Holder<Biome> plains = registryAccess.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        FixedBiomeSource biomeSource = new FixedBiomeSource(plains);
        IrisModdedChunkGenerator generator = new IrisModdedChunkGenerator(biomeSource, packKey);
        generator.repoint(packKey, seed);
        LevelStem stem = new LevelStem(dimensionType, generator);

        WorldData worldData = server.getWorldData();
        ServerLevelData overworldData = worldData.overworldData();
        DerivedLevelData derivedLevelData = new DerivedLevelData(worldData, overworldData);

        Executor executor = serverAccess.levelExecutor(server);
        LevelStorageSource.LevelStorageAccess storage = serverAccess.levelStorage(server);
        long obfuscatedSeed = BiomeManager.obfuscateSeed(seed);

        ServerLevel level = new ServerLevel(
                server,
                executor,
                storage,
                derivedLevelData,
                key,
                stem,
                false,
                obfuscatedSeed,
                List.of(),
                false);

        serverAccess.putLevel(server, key, level);
        server.getPlayerList().addWorldborderListener(level);
        return new Handle(dimensionId, packKey, seed, level, generator);
    }

    private static void evacuate(MinecraftServer server, ServerLevel from) {
        ServerLevel fallback = server.overworld();
        if (fallback == from) {
            return;
        }
        int spawnX = 0;
        int spawnZ = 0;
        int spawnY = fallback.getHeight(Heightmap.Types.MOTION_BLOCKING, spawnX, spawnZ);
        for (ServerPlayer player : new ArrayList<>(from.players())) {
            player.teleportTo(fallback, spawnX + 0.5D, spawnY, spawnZ + 0.5D, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        }
    }

    private static ResourceKey<Level> levelKey(String dimensionId) {
        Identifier identifier = Identifier.parse(dimensionId);
        return ResourceKey.create(Registries.DIMENSION, identifier);
    }

    private static ModdedServerAccess requireAccess() {
        ModdedServerAccess bound = access;
        if (bound == null) {
            throw new IllegalStateException("Iris modded server access is not bound; the loader bootstrap must bind ModdedServerAccess before runtime dimension injection");
        }
        return bound;
    }

    public record Handle(String dimensionId, String packKey, long seed, ServerLevel level, IrisModdedChunkGenerator generator) {
    }
}
