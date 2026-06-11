/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.engine.framework;

import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.documentation.BlockCoordinates;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.math.RollingSequence;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.scheduling.J;

public interface EngineMode extends Staged {
    RollingSequence r = new RollingSequence(64);
    RollingSequence r2 = new RollingSequence(256);

    void close();

    Engine getEngine();

    default MultiBurst burst() {
        return getEngine().burst();
    }

    default EngineStage burst(EngineStage... stages) {
        return (x, z, blocks, biomes, multicore, ctx) -> {
            BurstExecutor e = burst().burst(stages.length);
            e.setMulticore(multicore);

            for (EngineStage i : stages) {
                e.queue(() -> i.generate(x, z, blocks, biomes, multicore, ctx));
            }

            e.complete();
        };
    }

    default IrisComplex getComplex() {
        return getEngine().getComplex();
    }

    default EngineMantle getMantle() {
        return getEngine().getMantle();
    }

    default void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        getMantle().generateMatter(x, z, multicore, context);
    }

    @BlockCoordinates
    default void generate(int x, int z, Hunk<PlatformBlockState> blocks, Hunk<PlatformBiome> biomes, boolean multicore) {
        IrisContext context = IrisContext.getOr(getEngine());
        boolean cacheContext = true;
        if (J.isFolia() && getEngine().getWorld().hasRealWorld() && shouldDisableContextCacheForMaintenance()) {
            cacheContext = false;
        }
        ChunkContext.PrefillPlan prefillPlan = cacheContext ? ChunkContext.PrefillPlan.NO_CAVE : ChunkContext.PrefillPlan.NONE;
        ChunkContext ctx = new ChunkContext(x, z, getComplex(), context.getGenerationSessionId(), cacheContext, prefillPlan, getEngine().getMetrics());
        context.setChunkContext(ctx);

        EngineStage[] stages = getStages().toArray(new EngineStage[0]);
        for (EngineStage i : stages) {
            i.generate(x, z, blocks, biomes, multicore, ctx);
        }
    }

    static boolean shouldDisableContextCacheForMaintenance(boolean maintenanceActive, boolean pregeneratorTargetsWorld) {
        return maintenanceActive && !pregeneratorTargetsWorld;
    }

    private boolean shouldDisableContextCacheForMaintenance() {
        boolean maintenanceActive = IrisToolbelt.isWorldMaintenanceActive(getEngine().getWorld().realWorld());
        if (!maintenanceActive) {
            return false;
        }

        PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
        boolean pregeneratorTargetsWorld = pregeneratorJob != null && pregeneratorJob.targetsWorld(getEngine().getWorld().realWorld());
        return shouldDisableContextCacheForMaintenance(maintenanceActive, pregeneratorTargetsWorld);
    }
}
