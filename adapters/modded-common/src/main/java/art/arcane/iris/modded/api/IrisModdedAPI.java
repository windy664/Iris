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

package art.arcane.iris.modded.api;

import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.command.ModdedPregenJob;
import art.arcane.iris.modded.command.ModdedPregenMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class IrisModdedAPI {
    private static final Map<ServerLevel, AtomicInteger> WORLD_MAINTENANCE_DEPTH = new ConcurrentHashMap<>();

    private IrisModdedAPI() {
    }

    public static boolean isIrisLevel(ServerLevel level) {
        if (level == null) {
            return false;
        }
        return level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator;
    }

    public static boolean isStudioLevel(ServerLevel level) {
        Engine engine = getEngine(level);
        return engine != null && engine.isStudio();
    }

    public static Engine getEngine(ServerLevel level) {
        if (level == null) {
            return null;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof IrisModdedChunkGenerator irisGenerator)) {
            return null;
        }
        try {
            return irisGenerator.commandEngine();
        } catch (Throwable error) {
            return null;
        }
    }

    public static boolean pregenerate(ServerLevel level, int radiusBlocks) {
        return pregenerate(level, radiusBlocks, 0, 0, ModdedPregenMode.ASYNC, false);
    }

    public static boolean pregenerate(ServerLevel level, int radiusBlocks, int centerBlockX, int centerBlockZ, ModdedPregenMode mode, boolean cached) {
        Engine engine = getEngine(level);
        if (engine == null) {
            return false;
        }
        return ModdedPregenJob.start(level.getServer(), level, engine, radiusBlocks, centerBlockX, centerBlockZ, false, mode, cached);
    }

    public static <T> T getMantleData(ServerLevel level, int x, int y, int z, Class<T> type) {
        Engine engine = getEngine(level);
        if (engine == null) {
            return null;
        }
        return engine.getMantle().getMantle().get(x, y - engine.getMinHeight(), z, type);
    }

    public static <T> void setMantleData(ServerLevel level, int x, int y, int z, T data) {
        Engine engine = getEngine(level);
        if (engine == null || data == null) {
            return;
        }
        engine.getMantle().getMantle().set(x, y - engine.getMinHeight(), z, data);
    }

    public static <T> void deleteMantleData(ServerLevel level, int x, int y, int z, Class<T> type) {
        Engine engine = getEngine(level);
        if (engine == null) {
            return;
        }
        engine.getMantle().getMantle().remove(x, y - engine.getMinHeight(), z, type);
    }

    public static void retainMantleDataForSlice(Class<?> sliceType) {
        if (sliceType == null) {
            return;
        }
        IrisToolbelt.retainMantleDataForSlice(sliceType.getCanonicalName());
    }

    public static void registerProvider(ModdedDataProvider provider) {
        ModdedCustomContentRegistry.register(provider);
    }

    public static void registerCustomBlockData(String namespace, String key, String state) {
        ModdedCustomContentRegistry.registerCustomBlockData(namespace, key, state);
    }

    public static void beginWorldMaintenance(ServerLevel level) {
        if (level == null) {
            return;
        }
        WORLD_MAINTENANCE_DEPTH.computeIfAbsent(level, (ServerLevel l) -> new AtomicInteger()).incrementAndGet();
    }

    public static void endWorldMaintenance(ServerLevel level) {
        if (level == null) {
            return;
        }
        AtomicInteger counter = WORLD_MAINTENANCE_DEPTH.get(level);
        if (counter == null) {
            return;
        }
        if (counter.decrementAndGet() <= 0) {
            WORLD_MAINTENANCE_DEPTH.remove(level);
        }
    }

    public static boolean isWorldMaintenanceActive(ServerLevel level) {
        if (level == null) {
            return false;
        }
        AtomicInteger counter = WORLD_MAINTENANCE_DEPTH.get(level);
        return counter != null && counter.get() > 0;
    }
}
