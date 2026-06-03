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

import art.arcane.iris.engine.UpperDimensionContext;
import art.arcane.iris.engine.actuator.IrisDecorantActuator;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.iris.engine.object.IrisDimensionCarvingResolver;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.common.data.B;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.PowerOfTwoCoordinates;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.matter.slices.MarkerMatter;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.Data;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class IrisCarveModifier extends EngineAssignedModifier<BlockData> {
    private static final ThreadLocal<CarveScratch> SCRATCH = ThreadLocal.withInitial(CarveScratch::new);
    private static final int CAVE_BIOME_BLEND_RADIUS = 3;
    private static final int CAVE_BIOME_BLEND_CENTER_WEIGHT = 4;
    private static final int CAVE_BIOME_BLEND_TOTAL_WEIGHT = 8;
    private final RNG rng;
    private final BlockData AIR = Material.CAVE_AIR.createBlockData();
    private final BlockData LAVA = Material.LAVA.createBlockData();
    private final IrisDecorantActuator decorant;

    public IrisCarveModifier(Engine engine) {
        super(engine, "Carve");
        rng = new RNG(getEngine().getSeedManager().getCarve());
        decorant = new IrisDecorantActuator(engine);
    }

    @Override
    @ChunkCoordinates
    public void onModify(int x, int z, Hunk<BlockData> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        Mantle<Matter> mantle = getEngine().getMantle().getMantle();
        IrisDimensionCarvingResolver.State resolverState = new IrisDimensionCarvingResolver.State();
        Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache = new Long2ObjectOpenHashMap<>(2048);
        CarveScratch scratch = SCRATCH.get();
        scratch.reset();
        PackedWallBuffer walls = scratch.walls;
        ColumnMask[] columnMasks = scratch.columnMasks;
        ColumnMask[] boundaryMasks = scratch.boundaryMasks;
        MatterCavern[] boundaryCaverns = scratch.boundaryCaverns;
        int[] surfaceHeights = scratch.surfaceHeights;
        Map<String, IrisBiome> customBiomeCache = scratch.customBiomeCache;
        UpperDimensionContext upperCtx = getEngine().getUpperContext();
        boolean protectUpper = upperCtx != null && !getEngine().getDimension().isUpperDimensionCarving();
        int[] upperSurfaceHeights = protectUpper ? scratch.getOrCreateUpperSurfaceHeights() : null;
        int chunkBlockX = PowerOfTwoCoordinates.chunkToBlock(x);
        int chunkBlockZ = PowerOfTwoCoordinates.chunkToBlock(z);
        for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
            int localX = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
            int localZ = columnIndex & 15;
            surfaceHeights[columnIndex] = context.getRoundedHeight(localX, localZ);
            if (protectUpper) {
                int worldX = localX + chunkBlockX;
                int worldZ = localZ + chunkBlockZ;
                int rawUpper = upperCtx.getUpperSurfaceY(worldX, worldZ);
                int gap = getEngine().getDimension().getUpperDimensionGap();
                upperSurfaceHeights[columnIndex] = Math.max(rawUpper, surfaceHeights[columnIndex] + gap);
            }
        }

        MantleChunk<Matter> mc = mantle.getChunk(x, z).use();
        try {
            PrecisionStopwatch resolveStopwatch = PrecisionStopwatch.start();
            mc.iterate(MatterCavern.class, (xx, yy, zz, c) -> {
                if (c == null) {
                    return;
                }

                if (yy >= getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight() || yy <= 0) {
                    return;
                }

                int rx = xx & 15;
                int rz = zz & 15;
                int columnIndex = PowerOfTwoCoordinates.packLocal16(rx, rz);

                if (upperSurfaceHeights != null && yy >= upperSurfaceHeights[columnIndex]) {
                    return;
                }

                BlockData current = output.getRaw(rx, yy, rz);

                if (B.isFluid(current)) {
                    return;
                }

                columnMasks[columnIndex].add(yy);

                if (rz < 15 && mc.get(xx, yy, zz + 1, MatterCavern.class) == null) {
                    walls.put(rx, yy, rz + 1, c);
                }

                if (rx < 15 && mc.get(xx + 1, yy, zz, MatterCavern.class) == null) {
                    walls.put(rx + 1, yy, rz, c);
                }

                if (rz > 0 && mc.get(xx, yy, zz - 1, MatterCavern.class) == null) {
                    walls.put(rx, yy, rz - 1, c);
                }

                if (rx > 0 && mc.get(xx - 1, yy, zz, MatterCavern.class) == null) {
                    walls.put(rx - 1, yy, rz, c);
                }

                if (current.getMaterial().isAir()) {
                    return;
                }

                if (c.isWater()) {
                    output.setRaw(rx, yy, rz, context.getFluid().get(rx, rz));
                } else if (c.isLava()) {
                    output.setRaw(rx, yy, rz, LAVA);
                } else if (c.getLiquid() == 3) {
                    output.setRaw(rx, yy, rz, AIR);
                } else if (getEngine().getDimension().getCaveLavaHeight() > yy) {
                    output.setRaw(rx, yy, rz, LAVA);
                } else {
                    output.setRaw(rx, yy, rz, AIR);
                }
            });
            addCrossChunkBoundaryWalls(mantle, mc, walls, boundaryMasks, boundaryCaverns, x, z, surfaceHeights);
            getEngine().getMetrics().getCarveResolve().put(resolveStopwatch.getMilliseconds());

            PrecisionStopwatch applyStopwatch = PrecisionStopwatch.start();
            try {
                walls.forEach((rx, yy, rz, cavern) -> {
                    int worldX = rx + PowerOfTwoCoordinates.chunkToBlock(x);
                    int worldZ = rz + PowerOfTwoCoordinates.chunkToBlock(z);
                    String customBiome = cavern.getCustomBiome();
                    IrisBiome biome = customBiome.isEmpty()
                            ? resolveCaveBiome(caveBiomeCache, worldX, yy, worldZ, resolverState)
                            : resolveCustomBiome(customBiomeCache, customBiome);

                    if (biome != null) {
                        biome.setInferredType(InferredType.CAVE);
                        BlockData data = biome.getWall().get(rng, worldX, yy, worldZ, getData());
                        int columnIndex = PowerOfTwoCoordinates.packLocal16(rx, rz);

                        if (data != null && B.isSolid(output.getRaw(rx, yy, rz)) && yy < surfaceHeights[columnIndex]) {
                            output.setRaw(rx, yy, rz, data);
                        }
                    }
                });

                for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
                    processColumnFromMask(output, mc, mantle, columnMasks[columnIndex], columnIndex, x, z, resolverState, caveBiomeCache);
                }

                for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
                    if (boundaryMasks[columnIndex].isEmpty() || !columnMasks[columnIndex].isEmpty()) {
                        continue;
                    }
                    MatterCavern cavern = boundaryCaverns[columnIndex];
                    if (cavern == null) {
                        continue;
                    }
                    processBoundaryColumnFromMask(output, boundaryMasks[columnIndex], cavern, columnIndex, x, z, resolverState, caveBiomeCache, customBiomeCache);
                }
            } finally {
                getEngine().getMetrics().getCarveApply().put(applyStopwatch.getMilliseconds());
            }
        } finally {
            getEngine().getMetrics().getCave().put(p.getMilliseconds());
            mc.release();
        }
    }

    private void addCrossChunkBoundaryWalls(
            Mantle<Matter> mantle,
            MantleChunk<Matter> mc,
            PackedWallBuffer walls,
            ColumnMask[] boundaryMasks,
            MatterCavern[] boundaryCaverns,
            int chunkX,
            int chunkZ,
            int[] surfaceHeights
    ) {
        int baseX = PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int baseZ = PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        int maxSurfaceY = 0;
        for (int index = 0; index < surfaceHeights.length; index++) {
            if (surfaceHeights[index] > maxSurfaceY) {
                maxSurfaceY = surfaceHeights[index];
            }
        }
        int maxY = Math.min(getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight() - 1, maxSurfaceY + 1);
        if (maxY < 1) {
            return;
        }

        boolean westLoaded = mantle.hasTectonicPlate(((chunkX - 1) >> 5), (chunkZ >> 5));
        boolean eastLoaded = mantle.hasTectonicPlate(((chunkX + 1) >> 5), (chunkZ >> 5));
        boolean northLoaded = mantle.hasTectonicPlate((chunkX >> 5), ((chunkZ - 1) >> 5));
        boolean southLoaded = mantle.hasTectonicPlate((chunkX >> 5), ((chunkZ + 1) >> 5));
        if (!westLoaded && !eastLoaded && !northLoaded && !southLoaded) {
            return;
        }

        for (int yy = 1; yy <= maxY; yy++) {
            for (int offset = 0; offset < 16; offset++) {
                if (westLoaded) {
                    tryAddBoundaryWall(mantle, mc, walls, boundaryMasks, boundaryCaverns, 0, yy, offset, baseX, baseZ, -1, 0);
                }
                if (eastLoaded) {
                    tryAddBoundaryWall(mantle, mc, walls, boundaryMasks, boundaryCaverns, 15, yy, offset, baseX, baseZ, 1, 0);
                }
                if (northLoaded) {
                    tryAddBoundaryWall(mantle, mc, walls, boundaryMasks, boundaryCaverns, offset, yy, 0, baseX, baseZ, 0, -1);
                }
                if (southLoaded) {
                    tryAddBoundaryWall(mantle, mc, walls, boundaryMasks, boundaryCaverns, offset, yy, 15, baseX, baseZ, 0, 1);
                }
            }
        }
    }

    private void tryAddBoundaryWall(
            Mantle<Matter> mantle,
            MantleChunk<Matter> mc,
            PackedWallBuffer walls,
            ColumnMask[] boundaryMasks,
            MatterCavern[] boundaryCaverns,
            int localX,
            int yy,
            int localZ,
            int baseX,
            int baseZ,
            int dx,
            int dz
    ) {
        int worldX = baseX + localX;
        int worldZ = baseZ + localZ;
        if (mc.get(worldX, yy, worldZ, MatterCavern.class) != null) {
            return;
        }
        MatterCavern neighbor = mantle.get(worldX + dx, yy, worldZ + dz, MatterCavern.class);
        if (neighbor == null) {
            return;
        }
        walls.put(localX, yy, localZ, neighbor);
        int columnIndex = PowerOfTwoCoordinates.packLocal16(localX, localZ);
        boundaryMasks[columnIndex].add(yy);
        if (boundaryCaverns[columnIndex] == null) {
            boundaryCaverns[columnIndex] = neighbor;
        }
    }

    private void processColumnFromMask(
            Hunk<BlockData> output,
            MantleChunk<Matter> mc,
            Mantle<Matter> mantle,
            ColumnMask columnMask,
            int columnIndex,
            int chunkX,
            int chunkZ,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache
    ) {
        if (columnMask == null || columnMask.isEmpty()) {
            return;
        }

        int firstHeight = columnMask.nextSetBit(0);
        if (firstHeight < 0) {
            return;
        }

        int rx = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
        int rz = columnIndex & 15;
        int worldX = rx + PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int worldZ = rz + PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        CaveZone zone = new CaveZone();
        zone.setFloor(firstHeight);
        int buf = firstHeight - 1;
        int y = firstHeight;

        while (y >= 0) {
            if (y >= 0 && y <= getEngine().getHeight()) {
                if (y == buf + 1) {
                    buf = y;
                    zone.ceiling = buf;
                } else if (zone.isValid(getEngine())) {
                    processZone(output, mc, mantle, zone, rx, rz, worldX, worldZ, resolverState, caveBiomeCache);
                    zone = new CaveZone();
                    zone.setFloor(y);
                    buf = y;
                } else {
                    zone = new CaveZone();
                    zone.setFloor(y);
                    buf = y;
                }
            }

            y = columnMask.nextSetBit(y + 1);
        }

        if (zone.isValid(getEngine())) {
            processZone(output, mc, mantle, zone, rx, rz, worldX, worldZ, resolverState, caveBiomeCache);
        }
    }

    private void processBoundaryColumnFromMask(
            Hunk<BlockData> output,
            ColumnMask boundaryMask,
            MatterCavern cavern,
            int columnIndex,
            int chunkX,
            int chunkZ,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            Map<String, IrisBiome> customBiomeCache
    ) {
        int firstHeight = boundaryMask.nextSetBit(0);
        if (firstHeight < 0) {
            return;
        }

        int rx = PowerOfTwoCoordinates.unpackLocal16X(columnIndex);
        int rz = columnIndex & 15;
        int worldX = rx + PowerOfTwoCoordinates.chunkToBlock(chunkX);
        int worldZ = rz + PowerOfTwoCoordinates.chunkToBlock(chunkZ);
        int zoneFloor = firstHeight;
        int zoneCeiling = firstHeight;
        int y = boundaryMask.nextSetBit(firstHeight + 1);

        while (y >= 0) {
            if (y == zoneCeiling + 1) {
                zoneCeiling = y;
            } else {
                paintBoundaryZone(output, cavern, rx, rz, worldX, worldZ, zoneFloor, zoneCeiling, resolverState, caveBiomeCache, customBiomeCache);
                zoneFloor = y;
                zoneCeiling = y;
            }
            y = boundaryMask.nextSetBit(y + 1);
        }

        paintBoundaryZone(output, cavern, rx, rz, worldX, worldZ, zoneFloor, zoneCeiling, resolverState, caveBiomeCache, customBiomeCache);
    }

    private void paintBoundaryZone(
            Hunk<BlockData> output,
            MatterCavern cavern,
            int rx,
            int rz,
            int worldX,
            int worldZ,
            int zoneFloor,
            int zoneCeiling,
            IrisDimensionCarvingResolver.State resolverState,
            Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache,
            Map<String, IrisBiome> customBiomeCache
    ) {
        int center = (zoneFloor + zoneCeiling) / 2;
        String customBiome = cavern.getCustomBiome();
        IrisBiome biome = customBiome.isEmpty()
                ? resolveCaveBiome(caveBiomeCache, worldX, center, worldZ, resolverState)
                : resolveCustomBiome(customBiomeCache, customBiome);

        if (biome == null) {
            return;
        }

        biome.setInferredType(InferredType.CAVE);

        KList<BlockData> floorLayers = biome.generateLayers(getDimension(), worldX, worldZ, rng, 3, zoneFloor, getData(), getComplex());
        for (int i = 0; i < zoneFloor - 1; i++) {
            if (!floorLayers.hasIndex(i)) {
                break;
            }

            int fy = zoneFloor - i - 1;
            if (fy < 0) {
                break;
            }

            BlockData down = output.getRaw(rx, fy, rz);
            if (!B.isSolid(down)) {
                break;
            }

            BlockData layer = floorLayers.get(i);
            if (B.isOre(down)) {
                output.setRaw(rx, fy, rz, B.toDeepSlateOre(down, layer));
                continue;
            }

            output.setRaw(rx, fy, rz, layer);
        }

        int worldMaxY = getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight();
        KList<BlockData> ceilingLayers = biome.generateCeilingLayers(getDimension(), worldX, worldZ, rng, 3, zoneCeiling, getData(), getComplex());
        for (int i = 0; i < ceilingLayers.size(); i++) {
            int cy = zoneCeiling + i + 1;
            if (cy >= worldMaxY) {
                break;
            }

            BlockData up = output.getRaw(rx, cy, rz);
            if (!B.isSolid(up)) {
                continue;
            }

            BlockData layer = ceilingLayers.get(i);
            if (B.isOre(up)) {
                output.setRaw(rx, cy, rz, B.toDeepSlateOre(up, layer));
                continue;
            }

            output.setRaw(rx, cy, rz, layer);
        }
    }

    private void processZone(Hunk<BlockData> output, MantleChunk<Matter> mc, Mantle<Matter> mantle, CaveZone zone, int rx, int rz, int xx, int zz, IrisDimensionCarvingResolver.State resolverState, Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache) {
        int center = (zone.floor + zone.ceiling) / 2;
        String customBiome = "";

        if (B.isDecorant(output.getClosest(rx, zone.ceiling + 1, rz))) {
            output.setRaw(rx, zone.ceiling + 1, rz, AIR);
        }

        if (B.isDecorant(output.getRaw(rx, zone.ceiling, rz))) {
            output.setRaw(rx, zone.ceiling, rz, AIR);
        }

        if (M.r(1D / 16D)) {
            mantle.set(xx, zone.ceiling, zz, MarkerMatter.CAVE_CEILING);
        }

        if (M.r(1D / 16D)) {
            mantle.set(xx, zone.floor, zz, MarkerMatter.CAVE_FLOOR);
        }

        for (int i = zone.floor; i <= zone.ceiling; i++) {
            MatterCavern cavernData = (MatterCavern) mc.getOrCreate(PowerOfTwoCoordinates.floorDivPow2(i, 4)).slice(MatterCavern.class)
                    .get(rx, i & 15, rz);

            if (cavernData != null && !cavernData.getCustomBiome().isEmpty()) {
                customBiome = cavernData.getCustomBiome();
                break;
            }
        }

        IrisBiome biome = customBiome.isEmpty()
                ? resolveCaveBiome(caveBiomeCache, xx, center, zz, resolverState)
                : getEngine().getData().getBiomeLoader().load(customBiome);

        if (biome == null) {
            return;
        }

        biome.setInferredType(InferredType.CAVE);

        KList<BlockData> blocks = biome.generateLayers(getDimension(), xx, zz, rng, 3, zone.floor, getData(), getComplex());

        for (int i = 0; i < zone.floor - 1; i++) {
            if (!blocks.hasIndex(i)) {
                break;
            }
            int y = zone.floor - i - 1;

            BlockData b = blocks.get(i);
            BlockData down = output.getRaw(rx, y, rz);

            if (!B.isSolid(down)) {
                continue;
            }

            if (B.isOre(down)) {
                output.setRaw(rx, y, rz, B.toDeepSlateOre(down, b));
                continue;
            }

            output.setRaw(rx, y, rz, blocks.get(i));
        }

        blocks = biome.generateCeilingLayers(getDimension(), xx, zz, rng, 3, zone.ceiling, getData(), getComplex());

        if (zone.ceiling + 1 < mantle.getWorldHeight()) {
            for (int i = 0; i < zone.ceiling + 1; i++) {
                if (!blocks.hasIndex(i)) {
                    break;
                }

                BlockData b = blocks.get(i);
                BlockData up = output.getRaw(rx, zone.ceiling + i + 1, rz);

                if (!B.isSolid(up)) {
                    continue;
                }

                if (B.isOre(up)) {
                    output.setRaw(rx, zone.ceiling + i + 1, rz, B.toDeepSlateOre(up, b));
                    continue;
                }

                output.setRaw(rx, zone.ceiling + i + 1, rz, b);
            }
        }

        for (IrisDecorator decorator : biome.getDecorators()) {
            if (decorator.getPartOf().equals(IrisDecorationPart.NONE) && B.isSolid(output.getRaw(rx, zone.getFloor() - 1, rz))) {
                decorant.getSurfaceDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, biome, zone.getFloor() - 1, zone.airThickness());
            } else if (decorator.getPartOf().equals(IrisDecorationPart.CEILING) && B.isSolid(output.getRaw(rx, zone.getCeiling() + 1, rz))) {
                decorant.getCeilingDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, biome, zone.getCeiling(), zone.airThickness());
            }
        }
    }

    private IrisBiome resolveCaveBiome(Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, int x, int y, int z, IrisDimensionCarvingResolver.State resolverState) {
        IrisBiome center = sampleCaveBiome(caveBiomeCache, x, y, z, resolverState);
        if (center == null) {
            return null;
        }

        IrisBiome xPos = sampleCaveBiome(caveBiomeCache, x + CAVE_BIOME_BLEND_RADIUS, y, z, resolverState);
        IrisBiome xNeg = sampleCaveBiome(caveBiomeCache, x - CAVE_BIOME_BLEND_RADIUS, y, z, resolverState);
        IrisBiome zPos = sampleCaveBiome(caveBiomeCache, x, y, z + CAVE_BIOME_BLEND_RADIUS, resolverState);
        IrisBiome zNeg = sampleCaveBiome(caveBiomeCache, x, y, z - CAVE_BIOME_BLEND_RADIUS, resolverState);

        if (xPos == center && xNeg == center && zPos == center && zNeg == center) {
            return center;
        }

        int roll = Math.floorMod(rng.nextParallelRNG(BlockPosition.toLong(x, y, z)).nextInt(), CAVE_BIOME_BLEND_TOTAL_WEIGHT);
        if (roll < CAVE_BIOME_BLEND_CENTER_WEIGHT) {
            return center;
        }
        roll -= CAVE_BIOME_BLEND_CENTER_WEIGHT;
        if (roll == 0) {
            return xPos != null ? xPos : center;
        }
        if (roll == 1) {
            return xNeg != null ? xNeg : center;
        }
        if (roll == 2) {
            return zPos != null ? zPos : center;
        }
        return zNeg != null ? zNeg : center;
    }

    private IrisBiome sampleCaveBiome(Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache, int x, int y, int z, IrisDimensionCarvingResolver.State resolverState) {
        long key = BlockPosition.toLong(x, y, z);
        IrisBiome cachedBiome = caveBiomeCache.get(key);
        if (cachedBiome != null) {
            return cachedBiome;
        }

        IrisBiome resolvedBiome = getEngine().getCaveBiome(x, y, z, resolverState);
        if (resolvedBiome != null) {
            caveBiomeCache.put(key, resolvedBiome);
        }
        return resolvedBiome;
    }

    private IrisBiome resolveCustomBiome(Map<String, IrisBiome> customBiomeCache, String customBiome) {
        if (customBiomeCache.containsKey(customBiome)) {
            return customBiomeCache.get(customBiome);
        }

        IrisBiome loaded = getEngine().getData().getBiomeLoader().load(customBiome);
        customBiomeCache.put(customBiome, loaded);
        return loaded;
    }

    private static final class PackedWallBuffer {
        private static final int EMPTY_KEY = -1;
        private static final double LOAD_FACTOR = 0.75D;

        private int[] keys;
        private MatterCavern[] values;
        private int mask;
        private int resizeAt;
        private int size;

        private PackedWallBuffer(int expectedSize) {
            int capacity = 1;
            int minimumCapacity = Math.max(8, expectedSize);
            while (capacity < minimumCapacity) {
                capacity <<= 1;
            }

            this.keys = new int[capacity];
            Arrays.fill(this.keys, EMPTY_KEY);
            this.values = new MatterCavern[capacity];
            this.mask = capacity - 1;
            this.resizeAt = Math.max(1, (int) (capacity * LOAD_FACTOR));
        }

        private void put(int x, int y, int z, MatterCavern value) {
            int key = pack(x, y, z);
            int index = mix(key) & mask;

            while (true) {
                int existingKey = keys[index];
                if (existingKey == EMPTY_KEY) {
                    keys[index] = key;
                    values[index] = value;
                    size++;
                    if (size >= resizeAt) {
                        resize();
                    }
                    return;
                }

                if (existingKey == key) {
                    values[index] = value;
                    return;
                }

                index = (index + 1) & mask;
            }
        }

        private void forEach(PackedWallConsumer consumer) {
            for (int index = 0; index < keys.length; index++) {
                int key = keys[index];
                if (key == EMPTY_KEY) {
                    continue;
                }

                MatterCavern cavern = values[index];
                if (cavern == null) {
                    continue;
                }

                consumer.accept(unpackX(key), unpackY(key), unpackZ(key), cavern);
            }
        }

        private void clear() {
            Arrays.fill(keys, EMPTY_KEY);
            Arrays.fill(values, null);
            size = 0;
        }

        private void resize() {
            int[] oldKeys = keys;
            MatterCavern[] oldValues = values;
            int nextCapacity = oldKeys.length << 1;
            keys = new int[nextCapacity];
            Arrays.fill(keys, EMPTY_KEY);
            values = new MatterCavern[nextCapacity];
            mask = nextCapacity - 1;
            resizeAt = Math.max(1, (int) (nextCapacity * LOAD_FACTOR));
            size = 0;

            for (int index = 0; index < oldKeys.length; index++) {
                int key = oldKeys[index];
                if (key == EMPTY_KEY) {
                    continue;
                }

                MatterCavern value = oldValues[index];
                if (value == null) {
                    continue;
                }

                reinsert(key, value);
            }
        }

        private void reinsert(int key, MatterCavern value) {
            int index = mix(key) & mask;
            while (keys[index] != EMPTY_KEY) {
                index = (index + 1) & mask;
            }

            keys[index] = key;
            values[index] = value;
            size++;
        }

        private int pack(int x, int y, int z) {
            return (y << 8) | PowerOfTwoCoordinates.packLocal16(x & 15, z & 15);
        }

        private int unpackX(int key) {
            return PowerOfTwoCoordinates.unpackLocal16X(key & 255);
        }

        private int unpackY(int key) {
            return key >> 8;
        }

        private int unpackZ(int key) {
            return PowerOfTwoCoordinates.unpackLocal16Z(key);
        }

        private int mix(int value) {
            int mixed = value * 0x9E3779B9;
            return mixed ^ (mixed >>> 16);
        }
    }

    private static final class CarveScratch {
        private final ColumnMask[] columnMasks = new ColumnMask[256];
        private final ColumnMask[] boundaryMasks = new ColumnMask[256];
        private final MatterCavern[] boundaryCaverns = new MatterCavern[256];
        private final int[] surfaceHeights = new int[256];
        private final PackedWallBuffer walls = new PackedWallBuffer(512);
        private final Map<String, IrisBiome> customBiomeCache = new HashMap<>();
        private int[] upperSurfaceHeights;

        private CarveScratch() {
            for (int index = 0; index < columnMasks.length; index++) {
                columnMasks[index] = new ColumnMask();
                boundaryMasks[index] = new ColumnMask();
            }
        }

        private int[] getOrCreateUpperSurfaceHeights() {
            if (upperSurfaceHeights == null) {
                upperSurfaceHeights = new int[256];
            }
            return upperSurfaceHeights;
        }

        private void reset() {
            for (int index = 0; index < columnMasks.length; index++) {
                columnMasks[index].clear();
                boundaryMasks[index].clear();
                boundaryCaverns[index] = null;
            }
            walls.clear();
            customBiomeCache.clear();
        }
    }

    private static final class ColumnMask {
        private long[] words = new long[8];
        private int maxWord = -1;

        private void add(int y) {
            if (y < 0) {
                return;
            }

            int wordIndex = y >> 6;
            if (wordIndex >= words.length) {
                words = Arrays.copyOf(words, Math.max(words.length << 1, wordIndex + 1));
            }

            words[wordIndex] |= 1L << (y & 63);
            if (wordIndex > maxWord) {
                maxWord = wordIndex;
            }
        }

        private int nextSetBit(int fromBit) {
            if (maxWord < 0) {
                return -1;
            }

            int startBit = Math.max(0, fromBit);
            int wordIndex = startBit >> 6;
            if (wordIndex > maxWord) {
                return -1;
            }

            long word = words[wordIndex] & (-1L << (startBit & 63));
            while (true) {
                if (word != 0L) {
                    return (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                }

                wordIndex++;
                if (wordIndex > maxWord) {
                    return -1;
                }
                word = words[wordIndex];
            }
        }

        private boolean isEmpty() {
            return maxWord < 0;
        }

        private void clear() {
            if (maxWord < 0) {
                return;
            }

            for (int index = 0; index <= maxWord; index++) {
                words[index] = 0L;
            }
            maxWord = -1;
        }
    }

    @FunctionalInterface
    private interface PackedWallConsumer {
        void accept(int x, int y, int z, MatterCavern cavern);
    }

    @Data
    public static class CaveZone {
        private int ceiling = -1;
        private int floor = -1;

        public int airThickness() {
            return (ceiling - floor) - 1;
        }

        public boolean isValid(Engine engine) {
            return floor < ceiling && ceiling - floor >= 1 && floor >= 0 && ceiling <= engine.getHeight() && airThickness() > 0;
        }

        public String toString() {
            return floor + "-" + ceiling;
        }
    }
}
