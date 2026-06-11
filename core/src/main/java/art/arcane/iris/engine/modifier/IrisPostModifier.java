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

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisSlopeClip;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;

import java.util.concurrent.atomic.AtomicInteger;

public class IrisPostModifier extends EngineAssignedModifier<PlatformBlockState> {
    private static final PlatformBlockState AIR = B.getState("AIR");
    private static final PlatformBlockState WATER = B.getState("WATER");
    private final RNG rng;

    public IrisPostModifier(Engine engine) {
        super(engine, "Post");
        rng = new RNG(getEngine().getSeedManager().getPost());
    }

    @Override
    public void onModify(int x, int z, Hunk<PlatformBlockState> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        AtomicInteger i = new AtomicInteger();
        AtomicInteger j = new AtomicInteger();
        Hunk<PlatformBlockState> sync = output.synchronize();
        for (i.set(0); i.get() < output.getWidth(); i.getAndIncrement()) {
            for (j.set(0); j.get() < output.getDepth(); j.getAndIncrement()) {
                int ii = i.get();
                int jj = j.get();
                post(ii, jj, sync, ii + x, jj + z, context);
            }
        }

        getEngine().getMetrics().getPost().put(p.getMilliseconds());
    }

    private void post(int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData, int x, int z, ChunkContext context) {
        int h = getEngine().getMantle().trueHeight(x, z);
        int ha = getEngine().getMantle().trueHeight(x + 1, z);
        int hb = getEngine().getMantle().trueHeight(x, z + 1);
        int hc = getEngine().getMantle().trueHeight(x - 1, z);
        int hd = getEngine().getMantle().trueHeight(x, z - 1);

        // Floating Nibs
        int g = 0;

        if (h < 1) {
            return;
        }

        g += ha < h - 1 ? 1 : 0;
        g += hb < h - 1 ? 1 : 0;
        g += hc < h - 1 ? 1 : 0;
        g += hd < h - 1 ? 1 : 0;

        if (g == 4 && isAir(x, h - 1, z, currentPostX, currentPostZ, currentData)) {
            setPostBlock(x, h, z, AIR, currentPostX, currentPostZ, currentData);

            for (int i = h - 1; i > 0; i--) {
                if (!isAir(x, i, z, currentPostX, currentPostZ, currentData)) {
                    h = i;
                    break;
                }
            }
        }

        // Nibs
        g = 0;
        g += ha == h - 1 ? 1 : 0;
        g += hb == h - 1 ? 1 : 0;
        g += hc == h - 1 ? 1 : 0;
        g += hd == h - 1 ? 1 : 0;

        if (g >= 4) {
            PlatformBlockState bcState = getPostBlock(x, h, z, currentPostX, currentPostZ, currentData);
            PlatformBlockState bState = getPostBlock(x, h + 1, z, currentPostX, currentPostZ, currentData);
            BlockData b = (BlockData) bState.nativeHandle();
            Material m = ((BlockData) bcState.nativeHandle()).getMaterial();

            if ((b.getMaterial().isOccluding() && b.getMaterial().isSolid())) {
                if (m.isSolid()) {
                    setPostBlock(x, h, z, bState, currentPostX, currentPostZ, currentData);
                    h--;
                }
            }
        } else {
            // Potholes
            g = 0;
            g += ha == h + 1 ? 1 : 0;
            g += hb == h + 1 ? 1 : 0;
            g += hc == h + 1 ? 1 : 0;
            g += hd == h + 1 ? 1 : 0;

            if (g >= 4) {
                BlockData ba = (BlockData) getPostBlock(x, ha, z, currentPostX, currentPostZ, currentData).nativeHandle();
                BlockData bb = (BlockData) getPostBlock(x, hb, z, currentPostX, currentPostZ, currentData).nativeHandle();
                BlockData bc = (BlockData) getPostBlock(x, hc, z, currentPostX, currentPostZ, currentData).nativeHandle();
                BlockData bd = (BlockData) getPostBlock(x, hd, z, currentPostX, currentPostZ, currentData).nativeHandle();
                g = 0;
                g = BukkitBlockResolution.isSolid(ba) ? g + 1 : g;
                g = BukkitBlockResolution.isSolid(bb) ? g + 1 : g;
                g = BukkitBlockResolution.isSolid(bc) ? g + 1 : g;
                g = BukkitBlockResolution.isSolid(bd) ? g + 1 : g;

                if (g >= 3) {
                    setPostBlock(x, h + 1, z, getPostBlock(x, h, z, currentPostX, currentPostZ, currentData), currentPostX, currentPostZ, currentData);
                    h++;
                }
            }
        }

        // Wall Patcher
        IrisBiome biome = context.getBiome().get(currentPostX, currentPostZ);

        if (getDimension().isPostProcessingWalls()) {
            if (!biome.getWall().getPalette().isEmpty()) {
                if (ha < h - 2 || hb < h - 2 || hc < h - 2 || hd < h - 2) {
                    boolean brokeGround = false;
                    int max = Math.abs(Math.max(h - ha, Math.max(h - hb, Math.max(h - hc, h - hd))));

                    for (int i = h; i > h - max; i--) {
                        PlatformBlockState d = biome.getWall().get(rng, x + i, i + h, z + i, getData());

                        if (d != null) {
                            if (isAirOrWater(x, i, z, currentPostX, currentPostZ, currentData)) {
                                if (brokeGround) {
                                    break;
                                }

                                continue;
                            }

                            setPostBlock(x, i, z, d, currentPostX, currentPostZ, currentData);
                            brokeGround = true;
                        }
                    }
                }
            }
        }

        // Slab
        if (getDimension().isPostProcessingSlabs()) {
            //@builder
            if ((ha == h + 1 && isSolidNonSlab(x + 1, ha, z, currentPostX, currentPostZ, currentData))
                    || (hb == h + 1 && isSolidNonSlab(x, hb, z + 1, currentPostX, currentPostZ, currentData))
                    || (hc == h + 1 && isSolidNonSlab(x - 1, hc, z, currentPostX, currentPostZ, currentData))
                    || (hd == h + 1 && isSolidNonSlab(x, hd, z - 1, currentPostX, currentPostZ, currentData)))
            //@done
            {
                IrisSlopeClip sc = biome.getSlab().getSlopeCondition();
                PlatformBlockState d = sc.isValid(getComplex().getSlopeStream().get(x, z)) ? biome.getSlab().get(rng, x, h, z, getData()) : null;

                if (d != null) {
                    BlockData rawD = (BlockData) d.nativeHandle();
                    boolean cancel = BukkitBlockResolution.isAir(rawD);

                    if (rawD.getMaterial().equals(Material.SNOW) && h + 1 <= getDimension().getFluidHeight()) {
                        cancel = true;
                    }

                    if (isSnowLayer(x, h, z, currentPostX, currentPostZ, currentData)) {
                        cancel = true;
                    }

                    if (!cancel && isAirOrWater(x, h + 1, z, currentPostX, currentPostZ, currentData)) {
                        setPostBlock(x, h + 1, z, d, currentPostX, currentPostZ, currentData);
                        h++;
                    }
                }
            }
        }

        // Waterlogging
        BlockData b = (BlockData) getPostBlock(x, h, z, currentPostX, currentPostZ, currentData).nativeHandle();

        if (b instanceof Waterlogged) {
            Waterlogged ww = (Waterlogged) b.clone();
            boolean w = false;

            if (h <= getDimension().getFluidHeight() + 1) {
                if (isWaterOrWaterlogged(x, h + 1, z, currentPostX, currentPostZ, currentData)) {
                    w = true;
                } else if ((isWaterOrWaterlogged(x + 1, h, z, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x - 1, h, z, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x, h, z + 1, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x, h, z - 1, currentPostX, currentPostZ, currentData))) {
                    w = true;
                }
            }

            if (w != ww.isWaterlogged()) {
                ww.setWaterlogged(w);
                setPostBlock(x, h, z, BukkitBlockState.of(ww), currentPostX, currentPostZ, currentData);
            }
        } else if (b.getMaterial().equals(Material.AIR) && h <= getDimension().getFluidHeight()) {
            if ((isWaterOrWaterlogged(x + 1, h, z, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x - 1, h, z, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x, h, z + 1, currentPostX, currentPostZ, currentData) || isWaterOrWaterlogged(x, h, z - 1, currentPostX, currentPostZ, currentData))) {
                setPostBlock(x, h, z, WATER, currentPostX, currentPostZ, currentData);
            }
        }

        // Foliage
        b = (BlockData) getPostBlock(x, h + 1, z, currentPostX, currentPostZ, currentData).nativeHandle();

        if (BukkitBlockResolution.isVineBlock(b) && b instanceof MultipleFacing) {
            MultipleFacing f = (MultipleFacing) b.clone();
            int finalH = h + 1;

            f.getAllowedFaces().forEach(face -> {
                BlockData d = (BlockData) getPostBlock(x + face.getModX(), finalH + face.getModY(), z + face.getModZ(), currentPostX, currentPostZ, currentData).nativeHandle();
                f.setFace(face, !BukkitBlockResolution.isAir(d) && !BukkitBlockResolution.isVineBlock(d));
            });
            if (!f.equals(b)) {
                setPostBlock(x, h + 1, z, BukkitBlockState.of(f), currentPostX, currentPostZ, currentData);
            }
        }

        if (BukkitBlockResolution.isFoliage(b) || b.getMaterial().equals(Material.DEAD_BUSH)) {
            Material onto = ((BlockData) getPostBlock(x, h, z, currentPostX, currentPostZ, currentData).nativeHandle()).getMaterial();

            if (!BukkitBlockResolution.canPlaceOnto(b.getMaterial(), onto) && !BukkitBlockResolution.isDecorant(b)) {
                setPostBlock(x, h + 1, z, AIR, currentPostX, currentPostZ, currentData);
            }
        }
    }

    public boolean isAir(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.AIR) || d.getMaterial().equals(Material.CAVE_AIR);
    }

    public boolean hasGravity(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.SAND) || d.getMaterial().equals(Material.RED_SAND) || d.getMaterial().equals(Material.BLACK_CONCRETE_POWDER) || d.getMaterial().equals(Material.BLUE_CONCRETE_POWDER) || d.getMaterial().equals(Material.BROWN_CONCRETE_POWDER) || d.getMaterial().equals(Material.CYAN_CONCRETE_POWDER) || d.getMaterial().equals(Material.GRAY_CONCRETE_POWDER) || d.getMaterial().equals(Material.GREEN_CONCRETE_POWDER) || d.getMaterial().equals(Material.LIGHT_BLUE_CONCRETE_POWDER) || d.getMaterial().equals(Material.LIGHT_GRAY_CONCRETE_POWDER) || d.getMaterial().equals(Material.LIME_CONCRETE_POWDER) || d.getMaterial().equals(Material.MAGENTA_CONCRETE_POWDER) || d.getMaterial().equals(Material.ORANGE_CONCRETE_POWDER) || d.getMaterial().equals(Material.PINK_CONCRETE_POWDER) || d.getMaterial().equals(Material.PURPLE_CONCRETE_POWDER) || d.getMaterial().equals(Material.RED_CONCRETE_POWDER) || d.getMaterial().equals(Material.WHITE_CONCRETE_POWDER) || d.getMaterial().equals(Material.YELLOW_CONCRETE_POWDER);
    }

    public boolean isSolid(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().isSolid() && !BukkitBlockResolution.isVineBlock(d);
    }

    public boolean isSolidNonSlab(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().isSolid() && !(d instanceof Slab);
    }

    public boolean isAirOrWater(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.WATER) || d.getMaterial().equals(Material.AIR) || d.getMaterial().equals(Material.CAVE_AIR);
    }

    public boolean isSlab(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d instanceof Slab;
    }

    public boolean isSnowLayer(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.SNOW);
    }

    public boolean isWater(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.WATER);
    }

    public boolean isWaterOrWaterlogged(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d.getMaterial().equals(Material.WATER) || (d instanceof Waterlogged && ((Waterlogged) d).isWaterlogged());
    }

    public boolean isLiquid(int x, int y, int z, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        BlockData d = (BlockData) getPostBlock(x, y, z, currentPostX, currentPostZ, currentData).nativeHandle();
        return d instanceof Levelled;
    }

    public void setPostBlock(int x, int y, int z, PlatformBlockState d, int currentPostX, int currentPostZ, Hunk<PlatformBlockState> currentData) {
        if (y < currentData.getHeight()) {
            currentData.set(x & 15, y, z & 15, d);
        }
    }

    public PlatformBlockState getPostBlock(int x, int y, int z, int cpx, int cpz, Hunk<PlatformBlockState> h) {
        PlatformBlockState b = h.getClosest(x & 15, y, z & 15);

        return b == null ? AIR : b;
    }
}
