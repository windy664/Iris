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
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.math.RNG;

@ComponentFlag(ReservedFlag.JIGSAW)
public class IrisStructureComponent extends IrisMantleComponent {
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
        int surfaceY = getEngineMantle().getEngine().getHeight(sx, sz, true) + getEngineMantle().getEngine().getMinHeight();
        if (surfaceY < placement.getMinHeight() || surfaceY > placement.getMaxHeight()) {
            return;
        }

        String key = placement.getStructures().get(rng.i(0, placement.getStructures().size() - 1));
        IrisStructure structure = art.arcane.iris.core.loader.IrisData.loadAnyStructure(key, getData());
        if (structure == null) {
            return;
        }

        StructureAssembler assembler = new StructureAssembler(getData(), structure, sx, surfaceY, sz);
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            return;
        }

        ObjectPlaceMode mode = structure.getPlaceMode();
        if (mode == ObjectPlaceMode.STRUCTURE_PIECE || mode == ObjectPlaceMode.FLOATING) {
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
            int shift = surfaceY - lowest;
            for (PlacedStructurePiece p : pieces) {
                placeObject(writer, structure, p, ObjectPlaceMode.STRUCTURE_PIECE, p.getY() + shift, rng);
            }
        }
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
        int maxChunks = 0;

        for (IrisRegion region : dimension.getAllRegions(this::getData)) {
            maxChunks = Math.max(maxChunks, maxFrom(region.getStructures()));
        }
        for (IrisBiome biome : dimension.getAllBiomes(this::getData)) {
            maxChunks = Math.max(maxChunks, maxFrom(biome.getStructures()));
        }
        maxChunks = Math.max(maxChunks, maxFrom(dimension.getStructures()));

        return maxChunks * 16;
    }

    private int maxFrom(KList<IrisStructurePlacement> placements) {
        int max = 0;
        for (IrisStructurePlacement placement : placements) {
            if (placement.getRoute() == StructurePlacementRoute.NATIVE_AT_POINT) {
                continue;
            }
            for (String key : placement.getStructures()) {
                IrisStructure structure = art.arcane.iris.core.loader.IrisData.loadAnyStructure(key, getData());
                if (structure != null) {
                    max = Math.max(max, structure.getMaxSizeChunks());
                }
            }
        }
        return max;
    }
}
