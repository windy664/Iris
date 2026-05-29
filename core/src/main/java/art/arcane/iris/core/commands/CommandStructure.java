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

package art.arcane.iris.core.commands;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.structure.StructureImporter;
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.core.structure.VillageImporter;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.framework.StructurePlacementGrid;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.DirectorHelp;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Director(name = "structure", aliases = {"struct", "str"}, studio = true, description = "Iris structure tools (index, import, info)")
public class CommandStructure implements DirectorExecutor {
    @Director(description = "Show help tree for this command group", aliases = {"?"})
    public void help() {
        DirectorHelp.print(sender(), getClass());
    }

    @Director(description = "Regenerate structure-index.json listing all vanilla, datapack & iris structures", aliases = {"ls", "index"}, origin = DirectorOrigin.BOTH)
    public void list(
            @Param(description = "The dimension whose pack to index", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        File file = StructureIndexService.write(data);
        sender().sendMessage(C.GREEN + "Wrote structure index: " + C.WHITE + file.getPath());
    }

    @Director(description = "Import a vanilla/datapack structure NBT into a pack as editable Iris resources", aliases = {"imp"}, origin = DirectorOrigin.BOTH)
    public void importStructure(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "Structure key to import, e.g. minecraft:igloo/top or nova_structures:desert_temple")
            String key,
            @Param(description = "Name for the imported structure (defaults from the key)", defaultValue = "")
            String name,
            @Param(description = "overwrite | add-only | merge", defaultValue = "overwrite")
            String mode
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase());
        if (nk == null) {
            sender().sendMessage(C.RED + "Invalid structure key: " + key);
            return;
        }
        String n = name == null || name.isEmpty() ? StructureImporter.deriveName(nk) : name;
        StructureImporter.Result result = StructureImporter.importStructure(data, nk, n, StructureImporter.parseMode(mode));
        sender().sendMessage((result.success() ? C.GREEN : C.RED) + result.message());
        if (result.success()) {
            sender().sendMessage(C.GRAY + "Reference it from a biome/region/dimension structures list as '" + n + "'.");
        }
    }

    @Director(description = "Import a full vanilla/datapack JIGSAW structure (e.g. a village) by reconstructing its template-pool graph into editable Iris pools, pieces & connectors", aliases = {"iv", "village"}, origin = DirectorOrigin.BOTH)
    public void importVillage(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "Jigsaw structure key to import, e.g. minecraft:village_plains")
            String key,
            @Param(description = "Name for the imported structure (defaults from the key)", defaultValue = "")
            String name,
            @Param(description = "overwrite | add-only | merge", defaultValue = "overwrite")
            String mode
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase());
        if (nk == null) {
            sender().sendMessage(C.RED + "Invalid structure key: " + key);
            return;
        }
        String n = name == null || name.isEmpty() ? StructureImporter.deriveName(nk) : name;
        VillageImporter.Result result = VillageImporter.importVillage(data, nk, n, StructureImporter.parseMode(mode));
        sender().sendMessage((result.success() ? C.GREEN : C.RED) + result.message());
        if (result.success()) {
            sender().sendMessage(C.GRAY + "Reference it from a biome/region/dimension structures list as '" + n + "'.");
            sender().sendMessage(C.GRAY + "Inspect the rebuilt jigsaw graph with: /iris structure info " + dimension.getLoadKey() + " " + n);
        }
    }

    @Director(description = "Resolve an iris structure's jigsaw graph and report piece count & bounds", origin = DirectorOrigin.BOTH)
    public void info(
            @Param(description = "The dimension whose pack holds the structure", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "The iris structure key to inspect")
            String structure
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        IrisStructure s = IrisData.loadAnyStructure(structure, data);
        if (s == null) {
            sender().sendMessage(C.RED + "No iris structure '" + structure + "' in this pack");
            return;
        }
        StructureAssembler assembler = new StructureAssembler(data, s, 0, 64, 0);
        KList<PlacedStructurePiece> pieces = assembler.assemble(new RNG(1234));
        if (pieces == null || pieces.isEmpty()) {
            sender().sendMessage(C.RED + "Structure '" + structure + "' assembled 0 pieces (check startPool '" + s.getStartPool() + "')");
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece p : pieces) {
            minX = Math.min(minX, p.getMinX());
            minZ = Math.min(minZ, p.getMinZ());
            maxX = Math.max(maxX, p.getMaxX());
            maxZ = Math.max(maxZ, p.getMaxZ());
        }
        sender().sendMessage(C.GREEN + "Structure '" + structure + "': " + C.WHITE + pieces.size() + C.GREEN + " pieces, footprint " + C.WHITE + (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + C.GREEN + " blocks (sample seed 1234)");
    }

    @Director(description = "Assemble and place an iris structure at your location (studio testing)", aliases = {"p"}, origin = DirectorOrigin.PLAYER, sync = true)
    public void place(
            @Param(description = "The dimension whose pack holds the structure", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "The iris structure key to place")
            String structure
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        IrisStructure s = IrisData.loadAnyStructure(structure, data);
        if (s == null) {
            sender().sendMessage(C.RED + "No iris structure '" + structure + "' in this pack");
            return;
        }
        Location loc = player().getLocation();
        StructureAssembler assembler = new StructureAssembler(data, s, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        RNG rng = new RNG((long) loc.getBlockX() * 341873128712L + loc.getBlockZ());
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            sender().sendMessage(C.RED + "Structure '" + structure + "' assembled 0 pieces");
            return;
        }
        Map<Block, BlockData> future = new HashMap<>();
        IObjectPlacer placer = CommandObject.createPlacer(player().getWorld(), future);
        for (PlacedStructurePiece p : pieces) {
            IrisObjectPlacement config = new IrisObjectPlacement();
            config.setMode(ObjectPlaceMode.STRUCTURE_PIECE);
            config.setRotation(p.getRotation());
            config.getPlace().add(p.getObject().getLoadKey());
            if (!s.getEdit().isEmpty()) {
                config.setEdit(s.getEdit());
            }
            p.getObject().place(p.getX(), p.getY(), p.getZ(), placer, config, rng, null, null, data);
        }
        sender().sendMessage(C.GREEN + "Placed '" + structure + "' (" + pieces.size() + " pieces) at your location.");
    }

    @Director(description = "Find the nearest placement of an iris structure around you", aliases = {"l"}, origin = DirectorOrigin.PLAYER)
    public void locate(
            @Param(description = "The iris structure key to find")
            String structure,
            @Param(description = "Search radius in chunks", defaultValue = "96")
            int radius
    ) {
        World world = player().getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "You must be in an Iris world");
            return;
        }
        Engine engine = IrisToolbelt.access(world).getEngine();
        IrisData data = engine.getData();
        long seed = engine.getSeedManager().getMantle();
        int pcx = player().getLocation().getBlockX() >> 4;
        int pcz = player().getLocation().getBlockZ() >> 4;
        int max = Math.max(1, Math.min(radius, 512));
        for (int r = 0; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (chunkStarts(engine, seed, structure, cx, cz)) {
                        sender().sendMessage(C.GREEN + "Nearest '" + structure + "' starts near chunk " + cx + ", " + cz + " (block " + (cx << 4) + ", " + (cz << 4) + ")");
                        return;
                    }
                }
            }
        }
        sender().sendMessage(C.YELLOW + "No '" + structure + "' placement found within " + max + " chunks.");
    }

    private boolean chunkStarts(Engine engine, long seed, String structure, int cx, int cz) {
        int bx = 8 + (cx << 4);
        int bz = 8 + (cz << 4);
        IrisBiome biome = engine.getComplex().getTrueBiomeStream().get(bx, bz);
        IrisRegion region = engine.getComplex().getRegionStream().get(bx, bz);
        KList<IrisStructurePlacement> placements = new KList<>();
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (region != null) {
            placements.addAll(region.getStructures());
        }
        placements.addAll(engine.getDimension().getStructures());
        RNG rng = new RNG(Cache.key(cx, cz) + seed);
        for (IrisStructurePlacement placement : placements) {
            if (!placement.getStructures().contains(structure)) {
                continue;
            }
            if (StructurePlacementGrid.startsInChunk(placement, cx, cz, seed, rng)) {
                return true;
            }
        }
        return false;
    }
}
