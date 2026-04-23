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

package art.arcane.iris.engine.modifier;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.decorator.FloatingDecorator;
import art.arcane.iris.engine.decorator.IrisSeaSurfaceDecorator;
import static art.arcane.iris.engine.mantle.EngineMantle.AIR;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.framework.EngineDecorator;
import art.arcane.iris.engine.mantle.components.MantleFloatingObjectComponent;
import art.arcane.iris.engine.object.FloatingIslandSample;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisFloatingChildBiomes;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterBiomeInject;
import art.arcane.volmlib.util.matter.slices.BiomeInjectMatter;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.concurrent.atomic.AtomicLong;

public class IrisFloatingChildBiomeModifier extends EngineAssignedModifier<BlockData> {
    public static final long FLOATING_BASE_SEED_SALT = 0x5EED_F107_00F1B10CL;
    private static final AtomicLong columnsChecked = new AtomicLong();
    private static final AtomicLong samplesAccepted = new AtomicLong();
    private static final AtomicLong decorateInvocations = new AtomicLong();
    private static final AtomicLong decorateSkippedNotAir = new AtomicLong();
    private static final AtomicLong decorateSkippedNoInherit = new AtomicLong();
    private static final AtomicLong decoratePhaseColumns = new AtomicLong();
    private static final AtomicLong decoratePlaced = new AtomicLong();
    private static final AtomicLong decorateNoChange = new AtomicLong();
    private static final AtomicLong decorateFloorNull = new AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> floorMatHisto = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong decCandidatesNull = new AtomicLong();
    private static final AtomicLong lastReportMs = new AtomicLong(0L);
    private static final AtomicLong reportCycle = new AtomicLong(0L);
    private static final Runnable INC_DEC_CANDIDATES_NULL = () -> decCandidatesNull.incrementAndGet();
    private final RNG rng;
    private final EngineDecorator seaSurfaceDecorator;

    public static void reportFloatingStats() {
        StringBuilder topFloors = new StringBuilder();
        floorMatHisto.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(5)
                .forEach(e -> topFloors.append(' ').append(e.getKey()).append('=').append(e.getValue().get()));

        StringBuilder topAnchorY = new StringBuilder();
        MantleFloatingObjectComponent.anchorYHisto.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(5)
                .forEach(e -> topAnchorY.append(' ').append(e.getKey()).append('=').append(e.getValue().get()));

        art.arcane.iris.Iris.info("[floating-debug]"
                + " columns=" + columnsChecked.get()
                + " samples=" + samplesAccepted.get()
                + " decInvoke=" + decorateInvocations.get()
                + " decPlaced=" + decoratePlaced.get()
                + " decNoChange=" + decorateNoChange.get()
                + " decFloorNull=" + decorateFloorNull.get()
                + " decCandidatesNull=" + decCandidatesNull.get()
                + " decSkipNonAir=" + decorateSkippedNotAir.get()
                + " decSkipNoInherit=" + decorateSkippedNoInherit.get()
                + " decPhaseCols=" + decoratePhaseColumns.get()
                + " objAttempt=" + MantleFloatingObjectComponent.objectsAttempted.get()
                + " objPlaced=" + MantleFloatingObjectComponent.objectsPlaced.get()
                + " objNoFlat=" + MantleFloatingObjectComponent.objectsSkippedNoFlat.get()
                + " objNoInterior=" + MantleFloatingObjectComponent.objectsSkippedNoInterior.get()
                + " objRelax=" + MantleFloatingObjectComponent.objectsRelaxed.get()
                + " objShrinkDrop=" + MantleFloatingObjectComponent.objectsSkippedShrink.get()
                + " objNullObj=" + MantleFloatingObjectComponent.objectsSkippedNullObj.get()
                + " writeAttempt=" + MantleFloatingObjectComponent.writesAttemptedTotal.get()
                + " writeDropBelow=" + MantleFloatingObjectComponent.writesDroppedBelowTotal.get()
                + " writeDropOverhang=" + MantleFloatingObjectComponent.writesDroppedOverhangTotal.get()
                + " terrainMismatch=" + MantleFloatingObjectComponent.terrainMismatchWarnings.get()
                + " objInvAttempt=" + MantleFloatingObjectComponent.objectsInvertedAttempted.get()
                + " objInvPlaced=" + MantleFloatingObjectComponent.objectsInvertedPlaced.get()
                + " objInvNoFlat=" + MantleFloatingObjectComponent.objectsInvertedSkippedNoFlat.get()
                + " objInvFallbackNoInterior=" + MantleFloatingObjectComponent.objectsInvertedFallbackNoInterior.get()
                + " writesAboveBottom=" + MantleFloatingObjectComponent.writesDroppedAboveBottomTotal.get()
                + " writesBottomOverhang=" + MantleFloatingObjectComponent.writesDroppedBottomOverhangTotal.get()
                + " anchorY:" + (topAnchorY.length() == 0 ? " <none>" : topAnchorY.toString())
                + " topFloors:" + (topFloors.length() == 0 ? " <none>" : topFloors.toString()));
    }

    private static void maybeReport() {
        long now = System.currentTimeMillis();
        long last = lastReportMs.get();
        if (now - last >= 10000L && lastReportMs.compareAndSet(last, now)) {
            reportFloatingStats();
            if (reportCycle.incrementAndGet() >= 30) {
                reportCycle.set(0);
                resetAllCounters();
            }
        }
    }

    private static void resetAllCounters() {
        columnsChecked.set(0);
        samplesAccepted.set(0);
        decorateInvocations.set(0);
        decorateSkippedNotAir.set(0);
        decorateSkippedNoInherit.set(0);
        decoratePhaseColumns.set(0);
        decoratePlaced.set(0);
        decorateNoChange.set(0);
        decorateFloorNull.set(0);
        floorMatHisto.clear();
        decCandidatesNull.set(0);
        MantleFloatingObjectComponent.resetObjectCounters();
    }

    private static void recordFloorMat(String matKey) {
        if (floorMatHisto.size() < 32) {
            floorMatHisto.computeIfAbsent(matKey, k -> new AtomicLong()).incrementAndGet();
        } else {
            AtomicLong existing = floorMatHisto.get(matKey);
            if (existing != null) {
                existing.incrementAndGet();
            } else {
                floorMatHisto.computeIfAbsent("other", k -> new AtomicLong()).incrementAndGet();
            }
        }
    }

    public IrisFloatingChildBiomeModifier(Engine engine) {
        super(engine, "FloatingChildBiomes");
        rng = new RNG(engine.getSeedManager().getTerrain() ^ 0x7EB0A73F1DCE514DL);
        seaSurfaceDecorator = new IrisSeaSurfaceDecorator(engine);
    }

    @Override
    public void onModify(int x, int z, Hunk<BlockData> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        int chunkHeight = output.getHeight();
        IrisData data = getData();
        IrisDimension dimension = getDimension();
        IrisComplex complex = getComplex();
        long baseSeed = getEngine().getSeedManager().getTerrain() ^ FLOATING_BASE_SEED_SALT;

        for (int xf = 0; xf < 16; xf++) {
            for (int zf = 0; zf < 16; zf++) {
                int wx = x + xf;
                int wz = z + zf;
                IrisBiome parent = complex.getTrueBiomeStream().get(wx, wz);
                if (parent == null || parent.getFloatingChildBiomes() == null || parent.getFloatingChildBiomes().isEmpty()) {
                    continue;
                }
                columnsChecked.incrementAndGet();

                FloatingIslandSample sample = FloatingIslandSample.sampleMemoized(parent, wx, wz, chunkHeight, baseSeed, data, getEngine());
                if (sample == null) {
                    continue;
                }
                samplesAccepted.incrementAndGet();

                IrisFloatingChildBiomes entry = sample.entry;
                IrisBiome target = entry.getRealBiome(parent, data);
                long colSeed = FloatingIslandSample.columnSeed(baseSeed, wx, wz);
                RNG layerRng = rng.nextParallelRNG((int) (colSeed ^ 0x7A4E));
                int paletteDepth = Math.max(4, sample.solidCount + 4);
                KList<BlockData> blocks = target.generateLayers(dimension, wx, wz, layerRng, paletteDepth, paletteDepth, data, complex);
                if (blocks == null || blocks.isEmpty()) {
                    blocks = parent.generateLayers(dimension, wx, wz, layerRng, paletteDepth, paletteDepth, data, complex);
                }
                BlockData fallbackSolid = B.get("minecraft:stone");

                int depth = 0;
                for (int k = sample.topIdx; k >= 0; k--) {
                    if (!sample.solidMask[k]) {
                        continue;
                    }
                    int y = sample.islandBaseY + k;
                    if (y < 0 || y >= chunkHeight) {
                        continue;
                    }
                    BlockData block = null;
                    if (blocks != null && !blocks.isEmpty()) {
                        block = blocks.hasIndex(depth) ? blocks.get(depth) : blocks.getLast();
                    }
                    if (block == null) {
                        block = fallbackSolid;
                    }
                    if (block != null) {
                        output.set(xf, y, zf, block);
                    }
                    depth++;
                }

                Integer localFluidHeight = entry.getLocalFluidHeight();
                if (localFluidHeight != null && localFluidHeight > 0) {
                    BlockData fluid = B.get(entry.getFluidBlock());
                    if (fluid == null) {
                        fluid = B.get("minecraft:water");
                    }
                    int fluidCap = Math.min(sample.thickness - 1, localFluidHeight);
                    for (int k = 1; k <= fluidCap; k++) {
                        if (sample.solidMask[k]) {
                            continue;
                        }
                        int y = sample.islandBaseY + k;
                        if (y < 0 || y >= chunkHeight) {
                            continue;
                        }
                        boolean hasSolidBelow = false;
                        for (int kb = k - 1; kb >= 0; kb--) {
                            if (sample.solidMask[kb]) {
                                hasSolidBelow = true;
                                break;
                            }
                        }
                        if (hasSolidBelow) {
                            output.set(xf, y, zf, fluid);
                        }
                    }
                }

                if (target != null) {
                    writeIslandSkyBiome(target, wx, wz, sample, chunkHeight);
                }
            }
        }

        getEngine().getMetrics().getDeposit().put(p.getMilliseconds());
    }

    public void decorateColumns(int x, int z, Hunk<BlockData> output, boolean multicore, ChunkContext context) {
        int chunkHeight = output.getHeight();
        IrisData data = getData();
        IrisComplex complex = getComplex();
        long baseSeed = getEngine().getSeedManager().getTerrain() ^ FLOATING_BASE_SEED_SALT;

        for (int xf = 0; xf < 16; xf++) {
            for (int zf = 0; zf < 16; zf++) {
                int wx = x + xf;
                int wz = z + zf;
                IrisBiome parent = complex.getTrueBiomeStream().get(wx, wz);
                if (parent == null || parent.getFloatingChildBiomes() == null || parent.getFloatingChildBiomes().isEmpty()) {
                    continue;
                }
                FloatingIslandSample sample = FloatingIslandSample.sampleMemoized(parent, wx, wz, chunkHeight, baseSeed, data, getEngine());
                if (sample == null) {
                    continue;
                }
                decoratePhaseColumns.incrementAndGet();
                IrisFloatingChildBiomes entry = sample.entry;
                IrisBiome target = entry.getRealBiome(parent, data);

                if (!entry.isInheritDecorators() || target == null) {
                    decorateSkippedNoInherit.incrementAndGet();
                    continue;
                }

                int topY = sample.topY();
                int max = Math.max(1, chunkHeight - topY);
                if (topY + 1 < chunkHeight) {
                    BlockData above = output.get(xf, topY + 1, zf);
                    if (above == null || B.isAir(above)) {
                        decorateInvocations.incrementAndGet();
                        BlockData floor = topY >= 0 && topY < chunkHeight ? output.get(xf, topY, zf) : null;
                        if (floor == null) {
                            decorateFloorNull.incrementAndGet();
                        } else {
                            recordFloorMat(floor.getMaterial().getKey().getKey());
                        }
                        try {
                            RNG colRng = rng.nextParallelRNG((int) FloatingIslandSample.columnSeed(baseSeed, wx, wz));
                            int placed = FloatingDecorator.decorateColumn(getEngine(), target, IrisDecorationPart.NONE, xf, zf, wx, wz, topY, max, output, colRng, INC_DEC_CANDIDATES_NULL);
                            if (placed > 0) {
                                decoratePlaced.addAndGet(placed);
                            } else {
                                decorateNoChange.incrementAndGet();
                            }
                        } catch (Throwable e) {
                            art.arcane.iris.Iris.reportError(e);
                        }
                    } else {
                        decorateSkippedNotAir.incrementAndGet();
                    }
                }

                Integer localFluidHeight = entry.getLocalFluidHeight();
                if (localFluidHeight != null && localFluidHeight > 0) {
                    int fluidCap = Math.min(sample.thickness - 1, localFluidHeight);
                    int fluidTopY = -1;
                    for (int k = 1; k <= fluidCap; k++) {
                        if (sample.solidMask[k]) {
                            continue;
                        }
                        int y = sample.islandBaseY + k;
                        if (y < 0 || y >= chunkHeight) {
                            continue;
                        }
                        boolean hasSolidBelow = false;
                        for (int kb = k - 1; kb >= 0; kb--) {
                            if (sample.solidMask[kb]) {
                                hasSolidBelow = true;
                                break;
                            }
                        }
                        if (hasSolidBelow && y > fluidTopY) {
                            fluidTopY = y;
                        }
                    }
                    if (fluidTopY > 0 && fluidTopY + 1 < chunkHeight && B.isAir(output.get(xf, fluidTopY + 1, zf))) {
                        try {
                            seaSurfaceDecorator.decorate(xf, zf, wx, wx + 1, wx - 1, wz, wz + 1, wz - 1, output, target, fluidTopY, chunkHeight);
                        } catch (Throwable e) {
                            art.arcane.iris.Iris.reportError(e);
                        }
                    }
                }
            }
        }
        maybeReport();
    }

    private void writeIslandSkyBiome(IrisBiome target, int wx, int wz, FloatingIslandSample sample, int chunkHeight) {
        try {
            MatterBiomeInject matter;
            if (target.isCustom()) {
                IrisBiomeCustom custom = target.getCustomBiome(rng, wx, 0, wz);
                matter = BiomeInjectMatter.get(INMS.get().getBiomeBaseIdForKey(getDimension().getLoadKey() + ":" + custom.getId()));
            } else {
                Biome v = target.getSkyBiome(rng, wx, 0, wz);
                matter = BiomeInjectMatter.get(v);
            }
            int yFrom = Math.max(0, sample.islandBaseY);
            int yTo = Math.min(chunkHeight - 1, sample.islandBaseY + sample.topIdx);
            for (int y = yFrom; y <= yTo; y += 4) {
                getEngine().getMantle().getMantle().set(wx, y, wz, matter);
            }
        } catch (Throwable e) {
            art.arcane.iris.Iris.reportError(e);
        }
    }
}
