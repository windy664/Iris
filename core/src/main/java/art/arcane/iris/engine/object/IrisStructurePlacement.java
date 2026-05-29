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

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.iris.engine.object.annotations.RegistryListVanillaStructure;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Attaches structures to a biome, region or dimension and controls where and how often they generate. This is independent of a structure's own native generation, so you can add custom placements in tandem with native generation or instead of it.")
@Data
public class IrisStructurePlacement {
    @ArrayType(type = String.class, min = 1)
    @RegistryListResource(IrisStructure.class)
    @Desc("Iris structures to place here (route IRIS_PLACED). Use this for imported or hand-authored structures with full block control.")
    private KList<String> structures = new KList<>();

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Vanilla or datapack structure keys to place here faithfully (route NATIVE_AT_POINT), e.g. minecraft:igloo. Real loot/mobs/variety, no block editing.")
    private KList<String> vanilla = new KList<>();

    @Desc("How the placement is realized. Defaults sensibly by which list is populated, but you may set it explicitly.")
    private StructurePlacementRoute route = StructurePlacementRoute.IRIS_PLACED;

    @Desc("How start positions are scattered.")
    private StructureDistribution distribution = StructureDistribution.RANDOM_SPREAD;

    @MinNumber(1)
    @MaxNumber(4096)
    @Desc("RANDOM_SPREAD only: the grid cell size in chunks. One placement attempt per cell. Larger = rarer.")
    private int spacing = 32;

    @MinNumber(0)
    @Desc("RANDOM_SPREAD only: the minimum chunk separation between placements within the grid. Must be smaller than spacing.")
    private int separation = 8;

    @Desc("RANDOM_SPREAD only: a salt mixed into the placement RNG so different structures using the same spacing do not stack.")
    private int salt = 165745296;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("DENSITY only: the per-chunk probability (0-1) of a placement starting in a chunk.")
    private double density = 0.02;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: the number of placements distributed across the rings.")
    private int ringCount = 128;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: the ring spacing in chunks.")
    private int ringDistance = 32;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: how many placements share each ring before moving outward.")
    private int ringSpread = 3;

    @Desc("IRIS_PLACED only: rotation applied to the placed structure.")
    private IrisObjectRotation rotation = new IrisObjectRotation();

    @Desc("IRIS_PLACED only: translation applied to the placed structure.")
    private IrisObjectTranslate translate = new IrisObjectTranslate();

    @Desc("IRIS_PLACED only: scale applied to the placed structure.")
    private IrisObjectScale scale = new IrisObjectScale();

    @Desc("The minimum world Y this structure may start at.")
    private int minHeight = -2032;

    @Desc("The maximum world Y this structure may start at.")
    private int maxHeight = 2032;

    @Desc("If false, this placement is skipped underwater.")
    private boolean underwater = false;
}
