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

import art.arcane.iris.Iris;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.decorator.FloatingDecorator;
import art.arcane.iris.engine.decorator.IrisSeaSurfaceDecorator;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.framework.EngineDecorator;
import art.arcane.iris.engine.mantle.components.MantleFloatingObjectComponent;
import art.arcane.iris.engine.object.FloatingBottomPaletteMode;
import art.arcane.iris.engine.object.FloatingIslandSample;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomePaletteLayer;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisFloatingChildBiomes;
import art.arcane.iris.engine.object.IrisSlopeClip;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterBiomeInject;
import art.arcane.volmlib.util.matter.slices.BiomeInjectMatter;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import static art.arcane.iris.engine.mantle.EngineMantle.AIR;

public class IrisFloatingChildBiomeModifier extends EngineAssignedModifier<BlockData> {
    public static final long FLOATING_BASE_SEED_SALT = 0x5EED_F107_00F1B10CL;
    private static final Runnable NOOP_DECORATION_MISS = () -> {
    };
    private final RNG rng;
    private final EngineDecorator seaSurfaceDecorator;

    private static KList<BlockData> generateBottomPaletteLayers(IrisFloatingChildBiomes entry, IrisDimension dimension, double wx, double wz, RNG random, int paletteDepth, IrisData data, IrisComplex complex) {
        if (entry == null || entry.getBottomPaletteMode() != FloatingBottomPaletteMode.CUSTOM || entry.getBottomPalette() == null || entry.getBottomPalette().isEmpty()) {
            return null;
        }
        return generatePaletteLayers(dimension, entry.getBottomPalette(), wx, wz, random.nextParallelRNG(0xB0770B), paletteDepth, data, complex);
    }

    private static KList<BlockData> generatePaletteLayers(IrisDimension dimension, KList<IrisBiomePaletteLayer> layers, double wx, double wz, RNG random, int maxDepth, IrisData data, IrisComplex complex) {
        KList<BlockData> generated = new KList<>();
        if (layers == null || layers.isEmpty() || maxDepth <= 0) {
            return generated;
        }

        int generatorSeed = 7235;
        for (int i = 0; i < layers.size(); i++) {
            IrisBiomePaletteLayer layer = layers.get(i);
            CNG heightGenerator = layer.getHeightGenerator(random.nextParallelRNG((generatorSeed++) * generatorSeed * generatorSeed * generatorSeed), data);
            if (heightGenerator == null) {
                continue;
            }
            double layerDepth = heightGenerator.fit(layer.getMinHeight(), layer.getMaxHeight(), wx / layer.getZoom(), wz / layer.getZoom());
            IrisSlopeClip slopeClip = layer.getSlopeCondition();

            if (slopeClip != null && !slopeClip.isDefault() && complex != null && !slopeClip.isValid(complex.getSlopeStream().get(wx, wz))) {
                layerDepth = 0;
            }

            if (layerDepth <= 0) {
                continue;
            }

            for (int j = 0; j < layerDepth; j++) {
                if (generated.size() >= maxDepth) {
                    break;
                }

                try {
                    generated.add(layer.get(random.nextParallelRNG(i + j), (wx + j) / layer.getZoom(), j, (wz - j) / layer.getZoom(), data));
                } catch (Throwable e) {
                    Iris.reportError(e);
                    e.printStackTrace();
                }
            }

            if (generated.size() >= maxDepth) {
                break;
            }

            if (dimension != null && dimension.isExplodeBiomePalettes()) {
                BlockData barrier = B.get("minecraft:barrier");
                for (int j = 0; j < dimension.getExplodeBiomePaletteSize(); j++) {
                    generated.add(barrier);

                    if (generated.size() >= maxDepth) {
                        break;
                    }
                }
            }
        }

        return generated;
    }

    private static boolean usesBottomPalette(IrisFloatingChildBiomes entry) {
        FloatingBottomPaletteMode mode = entry == null || entry.getBottomPaletteMode() == null ? FloatingBottomPaletteMode.DEPTH : entry.getBottomPaletteMode();
        return mode != FloatingBottomPaletteMode.DEPTH;
    }

    private static int[] bottomDepths(FloatingIslandSample sample, int chunkHeight) {
        int[] bottomDepths = new int[sample.solidMask.length];
        for (int i = 0; i < bottomDepths.length; i++) {
            bottomDepths[i] = -1;
        }

        int depth = 0;
        int max = Math.min(sample.topIdx, sample.solidMask.length - 1);
        for (int k = 0; k <= max; k++) {
            if (!sample.solidMask[k]) {
                continue;
            }
            int y = sample.islandBaseY + k;
            if (y < 0 || y >= chunkHeight) {
                continue;
            }
            bottomDepths[k] = depth++;
        }

        return bottomDepths;
    }

    private static BlockData selectPaletteBlock(IrisFloatingChildBiomes entry, KList<BlockData> topBlocks, KList<BlockData> bottomBlocks, int topDepth, int bottomDepth, BlockData fallbackSolid) {
        FloatingBottomPaletteMode mode = entry == null || entry.getBottomPaletteMode() == null ? FloatingBottomPaletteMode.DEPTH : entry.getBottomPaletteMode();
        if (mode == FloatingBottomPaletteMode.MIRROR_TOP) {
            return paletteBlock(topBlocks, Math.min(topDepth, bottomDepth), fallbackSolid);
        }
        if (mode == FloatingBottomPaletteMode.CUSTOM && bottomDepth < topDepth) {
            if (bottomBlocks != null && !bottomBlocks.isEmpty()) {
                return paletteBlock(bottomBlocks, bottomDepth, fallbackSolid);
            }
            return paletteBlock(topBlocks, bottomDepth, fallbackSolid);
        }
        return paletteBlock(topBlocks, topDepth, fallbackSolid);
    }

    private static BlockData paletteBlock(KList<BlockData> blocks, int depth, BlockData fallbackSolid) {
        if (blocks == null || blocks.isEmpty()) {
            return fallbackSolid;
        }
        BlockData block = blocks.hasIndex(depth) ? blocks.get(depth) : blocks.getLast();
        return block == null ? fallbackSolid : block;
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
                FloatingIslandSample sample = FloatingIslandSample.sampleMemoized(parent, wx, wz, chunkHeight, baseSeed, data, getEngine());
                if (sample == null) {
                    continue;
                }

                IrisFloatingChildBiomes entry = sample.entry;
                IrisBiome target = entry.getRealBiome(parent, data);
                long colSeed = FloatingIslandSample.columnSeed(baseSeed, wx, wz);
                RNG layerRng = rng.nextParallelRNG((int) (colSeed ^ 0x7A4E));
                int paletteDepth = Math.max(4, sample.solidCount + 4);
                KList<BlockData> blocks = target.generateLayers(dimension, wx, wz, layerRng, paletteDepth, paletteDepth, data, complex);
                if (blocks == null || blocks.isEmpty()) {
                    blocks = parent.generateLayers(dimension, wx, wz, layerRng, paletteDepth, paletteDepth, data, complex);
                }
                KList<BlockData> bottomBlocks = generateBottomPaletteLayers(entry, dimension, wx, wz, layerRng, paletteDepth, data, complex);
                BlockData fallbackSolid = B.get("minecraft:stone");

                int[] bottomDepths = usesBottomPalette(entry) ? bottomDepths(sample, chunkHeight) : null;
                int depth = 0;
                for (int k = sample.topIdx; k >= 0; k--) {
                    if (!sample.solidMask[k]) {
                        continue;
                    }
                    int y = sample.islandBaseY + k;
                    if (y < 0 || y >= chunkHeight) {
                        continue;
                    }
                    int bottomDepth = bottomDepths == null || bottomDepths[k] < 0 ? depth : bottomDepths[k];
                    BlockData block = selectPaletteBlock(entry, blocks, bottomBlocks, depth, bottomDepth, fallbackSolid);
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
                IrisFloatingChildBiomes entry = sample.entry;
                IrisBiome target = entry.getRealBiome(parent, data);

                if (!entry.isInheritDecorators() || target == null) {
                    continue;
                }

                int topY = sample.topY();
                int max = Math.max(1, chunkHeight - topY);
                if (topY + 1 < chunkHeight) {
                    BlockData above = output.get(xf, topY + 1, zf);
                    if (above == null || B.isAir(above)) {
                        try {
                            RNG colRng = rng.nextParallelRNG((int) FloatingIslandSample.columnSeed(baseSeed, wx, wz));
                            FloatingDecorator.decorateColumn(getEngine(), target, IrisDecorationPart.NONE, xf, zf, wx, wz, topY, max, output, colRng, NOOP_DECORATION_MISS);
                        } catch (Throwable e) {
                            art.arcane.iris.Iris.reportError(e);
                        }
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
