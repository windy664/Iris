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

package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.framework.StructurePlacementGrid;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.IrisMantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.StructurePlacementRoute;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.block.data.BlockData;

@ComponentFlag(ReservedFlag.JIGSAW)
public class IrisStructureComponent extends IrisMantleComponent {
    private static final long MAX_BORE_VOLUME = 6_000_000L;
    private static final long MAX_OVERBORE_VOLUME = 48_000_000L;
    private static final BlockData CARVE_AIR = B.get("CAVE_AIR");

    public IrisStructureComponent(EngineMantle engineMantle) {
        super(engineMantle, ReservedFlag.JIGSAW, 1);
    }

    @Override
    @ChunkCoordinates
    public void generateLayer(MantleWriter writer, int x, int z, ChunkContext context) {
        IrisComplex complex = context.getComplex();
        int xxx = 8 + (x << 4);
        int zzz = 8 + (z << 4);
        IrisRegion region = complex.getRegionStream().get(xxx, zzz);
        IrisBiome biome = complex.getTrueBiomeStream().get(xxx, zzz);
        RNG rng = new RNG(Cache.key(x, z) + seed());

        KList<IrisStructurePlacement> placements = new KList<>();
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (region != null) {
            placements.addAll(region.getStructures());
        }
        placements.addAll(getDimension().getStructures());

        for (IrisStructurePlacement placement : placements) {
            placeFromPlacement(writer, placement, x, z, rng);
        }
    }

    @ChunkCoordinates
    private void placeFromPlacement(MantleWriter writer, IrisStructurePlacement placement, int cx, int cz, RNG rng) {
        if (placement.getRoute() == StructurePlacementRoute.NATIVE_AT_POINT) {
            return;
        }
        if (placement.getStructures().isEmpty()) {
            return;
        }
        if (!StructurePlacementGrid.startsInChunk(placement, cx, cz, seed(), rng)) {
            return;
        }

        int sx = (cx << 4) + rng.i(0, 15);
        int sz = (cz << 4) + rng.i(0, 15);
        int baseY;
        if (placement.isUnderground()) {
            int worldMinY = getEngineMantle().getEngine().getMinHeight() + 1;
            int worldMaxY = getEngineMantle().getEngine().getMinHeight() + getEngineMantle().getEngine().getHeight() - 1;
            int bandMin = Math.max(worldMinY, Math.min(placement.getMinHeight(), placement.getMaxHeight()));
            int bandMax = Math.min(worldMaxY, Math.max(placement.getMinHeight(), placement.getMaxHeight()));
            if (bandMin > bandMax) {
                return;
            }
            baseY = bandMin == bandMax ? bandMin : rng.i(bandMin, bandMax);
        } else {
            int surfaceY = getEngineMantle().getEngine().getHeight(sx, sz, true) + getEngineMantle().getEngine().getMinHeight();
            if (surfaceY < placement.getMinHeight() || surfaceY > placement.getMaxHeight()) {
                return;
            }
            baseY = surfaceY;
        }

        String key = placement.getStructures().get(rng.i(0, placement.getStructures().size() - 1));
        IrisStructure structure = art.arcane.iris.core.loader.IrisData.loadAnyStructure(key, getData());
        if (structure == null) {
            return;
        }

        StructureAssembler assembler = new StructureAssembler(getData(), structure, sx, baseY, sz);
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            return;
        }

        if (placement.isOverbore()) {
            overboreStructure(writer, pieces, placement.getOverboreRadius(), placement.getOverboreHeight(), placement.getOverboreFloor());
        } else if (placement.isBore()) {
            boreStructure(writer, pieces, placement.getBorePadding());
        }

        ObjectPlaceMode mode = structure.getPlaceMode();
        if (placement.isUnderground() || mode == ObjectPlaceMode.STRUCTURE_PIECE || mode == ObjectPlaceMode.FLOATING) {
            for (PlacedStructurePiece p : pieces) {
                placeObject(writer, structure, p, ObjectPlaceMode.STRUCTURE_PIECE, p.getY(), rng);
            }
        } else if (pieces.size() == 1) {
            placeObject(writer, structure, pieces.getFirst(), mode, -1, rng);
        } else {
            int lowest = Integer.MAX_VALUE;
            for (PlacedStructurePiece p : pieces) {
                lowest = Math.min(lowest, p.getMinY());
            }
            int shift = baseY - lowest;
            for (PlacedStructurePiece p : pieces) {
                placeObject(writer, structure, p, ObjectPlaceMode.STRUCTURE_PIECE, p.getY() + shift, rng);
            }
        }
    }

    private void boreStructure(MantleWriter writer, KList<PlacedStructurePiece> pieces, int padding) {
        int[] bounds = computePieceBounds(pieces);
        if (bounds == null) {
            return;
        }
        int pad = Math.max(0, padding);
        int minX = bounds[0] - pad;
        int minY = bounds[1];
        int minZ = bounds[2] - pad;
        int maxX = bounds[3] + pad;
        int maxY = bounds[4] + pad;
        int maxZ = bounds[5] + pad;
        int worldMin = getEngineMantle().getEngine().getMinHeight() + 1;
        int worldMax = getEngineMantle().getEngine().getMinHeight() + getEngineMantle().getEngine().getHeight() - 1;
        minY = Math.max(minY, worldMin);
        maxY = Math.min(maxY, worldMax);
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return;
        }
        long volume = (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
        if (volume > MAX_BORE_VOLUME) {
            Iris.warn("Skipping structure bore of " + volume + " blocks (cap " + MAX_BORE_VOLUME + "); use a smaller structure or larger spacing.");
            return;
        }
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    writer.set(bx, by, bz, CARVE_AIR);
                }
            }
        }
    }

    private void overboreStructure(MantleWriter writer, KList<PlacedStructurePiece> pieces, int radius, int ceiling, int floorDepth) {
        int[] bounds = computePieceBounds(pieces);
        if (bounds == null) {
            return;
        }
        int r = Math.max(0, radius);
        int boxMinX = bounds[0];
        int boxMinZ = bounds[2];
        int boxMaxX = bounds[3];
        int boxMaxZ = bounds[5];
        int worldMin = getEngineMantle().getEngine().getMinHeight() + 1;
        int worldMax = getEngineMantle().getEngine().getMinHeight() + getEngineMantle().getEngine().getHeight() - 1;
        int floorY = Math.max(worldMin, bounds[1] - Math.max(0, floorDepth));
        int apexY = Math.min(worldMax, bounds[4] + Math.max(0, ceiling));
        if (apexY < floorY) {
            return;
        }
        int expMinX = boxMinX - r;
        int expMaxX = boxMaxX + r;
        int expMinZ = boxMinZ - r;
        int expMaxZ = boxMaxZ + r;
        long volume = (long) (expMaxX - expMinX + 1) * (long) (apexY - floorY + 1) * (long) (expMaxZ - expMinZ + 1);
        if (volume > MAX_OVERBORE_VOLUME) {
            Iris.warn("Skipping structure overbore of " + volume + " blocks (cap " + MAX_OVERBORE_VOLUME + "); reduce overboreRadius or use larger spacing.");
            return;
        }
        int span = apexY - floorY;
        double rr = r <= 0 ? 1.0 : (double) r;

        RNG noiseRng = new RNG(seed() + Cache.key(boxMinX, boxMinZ));
        CNG outlineNoise = CNG.signature(noiseRng);
        CNG ceilNoise = CNG.signature(noiseRng.nextParallelRNG(0x51E10));
        CNG floorNoise = CNG.signature(noiseRng.nextParallelRNG(0xF1009));
        double outlineFreq = 0.045;
        double ceilFreq = 0.055;
        double floorFreq = 0.07;
        int floorAmp = Math.min(5, Math.max(1, span / 8));
        double ceilLump = Math.min(4.0, span * 0.15);

        if (IrisSettings.get().getGeneral().isDebug()) {
            Iris.info("Overbore carving cavern: box=[" + boxMinX + "," + floorY + "," + boxMinZ + " -> " + boxMaxX + "," + apexY + "," + boxMaxZ + "] radius=" + r + " volume=" + volume);
        }

        BlockData air = CARVE_AIR;
        for (int bx = expMinX; bx <= expMaxX; bx++) {
            int dx = bx < boxMinX ? boxMinX - bx : bx > boxMaxX ? bx - boxMaxX : 0;
            for (int bz = expMinZ; bz <= expMaxZ; bz++) {
                int dz = bz < boxMinZ ? boxMinZ - bz : bz > boxMaxZ ? bz - boxMaxZ : 0;
                int floorYcol = floorY + (int) Math.round(floorNoise.fitDouble(-1.0, 1.0, bx * floorFreq, bz * floorFreq) * floorAmp);
                if (floorYcol < worldMin) {
                    floorYcol = worldMin;
                }
                int columnCeil;
                if (dx == 0 && dz == 0) {
                    int bump = (int) Math.round(ceilNoise.fitDouble(0.0, 1.0, bx * ceilFreq, bz * ceilFreq) * ceilLump);
                    columnCeil = Math.min(worldMax, apexY - Math.max(0, bump));
                } else {
                    double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double outline = outlineNoise.fitDouble(-1.0, 1.0, bx * outlineFreq, bz * outlineFreq);
                    double rEff = rr * (0.80 + 0.35 * outline);
                    if (rEff < 1.0) {
                        rEff = 1.0;
                    }
                    if (dist > rEff) {
                        continue;
                    }
                    double t = dist / rEff;
                    double dome = Math.sqrt(Math.max(0.0, 1.0 - t * t));
                    double lump = ceilNoise.fitDouble(-1.0, 1.0, bx * ceilFreq, bz * ceilFreq);
                    double mix = dome * (0.80 + 0.40 * lump);
                    if (mix < 0.0) {
                        mix = 0.0;
                    }
                    columnCeil = floorYcol + (int) Math.round(span * mix);
                    if (columnCeil <= floorYcol) {
                        continue;
                    }
                    columnCeil = Math.min(worldMax, columnCeil);
                }
                for (int by = floorYcol; by <= columnCeil; by++) {
                    writer.set(bx, by, bz, air);
                }
            }
        }
    }

    private int[] computePieceBounds(KList<PlacedStructurePiece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece p : pieces) {
            minX = Math.min(minX, p.getMinX());
            minY = Math.min(minY, p.getMinY());
            minZ = Math.min(minZ, p.getMinZ());
            maxX = Math.max(maxX, p.getMaxX());
            maxY = Math.max(maxY, p.getMaxY());
            maxZ = Math.max(maxZ, p.getMaxZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private void placeObject(MantleWriter writer, IrisStructure structure, PlacedStructurePiece p, ObjectPlaceMode mode, int y, RNG rng) {
        IrisObject object = p.getObject();
        IrisObjectPlacement config = new IrisObjectPlacement();
        config.setMode(mode);
        config.setRotation(p.getRotation());
        config.getPlace().add(object.getLoadKey());
        if (!structure.getEdit().isEmpty()) {
            config.setEdit(structure.getEdit());
        }
        object.place(p.getX(), y, p.getZ(), writer, config, rng, null, null, getData());
    }

    @Override
    protected int computeRadius() {
        IrisDimension dimension = getDimension();
        int maxBlocks = 0;

        for (IrisRegion region : dimension.getAllRegions(this::getData)) {
            maxBlocks = Math.max(maxBlocks, maxBlocksFrom(region.getStructures()));
        }
        for (IrisBiome biome : dimension.getAllBiomes(this::getData)) {
            maxBlocks = Math.max(maxBlocks, maxBlocksFrom(biome.getStructures()));
        }
        maxBlocks = Math.max(maxBlocks, maxBlocksFrom(dimension.getStructures()));

        return maxBlocks;
    }

    private int maxBlocksFrom(KList<IrisStructurePlacement> placements) {
        int max = 0;
        for (IrisStructurePlacement placement : placements) {
            if (placement.getRoute() == StructurePlacementRoute.NATIVE_AT_POINT) {
                continue;
            }
            int carvePadding = placement.isOverbore() ? Math.max(0, placement.getOverboreRadius())
                    : placement.isBore() ? Math.max(0, placement.getBorePadding()) : 0;
            for (String key : placement.getStructures()) {
                IrisStructure structure = art.arcane.iris.core.loader.IrisData.loadAnyStructure(key, getData());
                if (structure != null) {
                    max = Math.max(max, Math.max(1, structure.getMaxSizeChunks()) * 16 + carvePadding);
                }
            }
        }
        return max;
    }
}
