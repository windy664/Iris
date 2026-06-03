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
import art.arcane.iris.core.structure.BulkStructureImporter;
import art.arcane.iris.core.structure.StructureImporter;
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.StructureSearchResult;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Director(name = "structure", aliases = {"struct", "str"}, description = "Iris structure tools (index, import, info)")
public class CommandStructure implements DirectorExecutor {
    @Director(description = "Regenerate structure-index.json listing all vanilla, datapack & iris structures", aliases = {"ls"}, origin = DirectorOrigin.BOTH)
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

    @Director(name = "import", description = "Import EVERY structure - vanilla AND ingested datapacks - into this pack as editable Iris resources, always overwriting. Rebuilds jigsaw structures (villages, outposts, datapack jigsaws) as editable pool/piece graphs, imports every structure template NBT as an object, and assembles the multi-template structures (shipwrecks, ruined portals, ocean ruins, nether fossils). Run after ingesting a datapack and restarting. Regenerate chunks or use a fresh world for the imported copies to place.", aliases = {"import-all", "reimport", "imp", "all"}, origin = DirectorOrigin.BOTH)
    public void importAll(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        sender().sendMessage(C.GREEN + "Importing all vanilla & datapack structures into " + C.WHITE + dimension.getLoadKey() + C.GREEN + " (overwrite)...");
        BulkStructureImporter.Report jigsaws = BulkStructureImporter.importAllVanilla(data, StructureImporter.Mode.OVERWRITE, true, sender());
        BulkStructureImporter.Report templates = BulkStructureImporter.importAllTemplates(data, StructureImporter.Mode.OVERWRITE, sender());
        BulkStructureImporter.Report groups = BulkStructureImporter.importTemplateGroups(data, StructureImporter.Mode.OVERWRITE, sender());
        int imported = jigsaws.imported() + templates.imported() + groups.imported();
        int failed = jigsaws.failed() + templates.failed() + groups.failed();
        sender().sendMessage(C.GREEN + "Import complete: " + C.WHITE + imported + C.GREEN + " structures/objects written, " + C.WHITE + failed + C.GREEN + " failed.");
        sender().sendMessage(C.GRAY + "Reference them from a biome/region/dimension 'structures' list, or run /iris structure list " + dimension.getLoadKey() + " to refresh the index. Regenerate chunks for changes to take effect.");
    }

    @Director(description = "Locate every vanilla/datapack/iris structure to verify which are locatable in this world. Heavy synchronous search per structure - keep the radius modest. 'Not found' can mean rarer than the radius, a different dimension (nether/end structures never appear in the overworld), or a structure that generates but cannot be located; generation itself happens during chunk decoration, independent of locate.", aliases = {"locateall"}, origin = DirectorOrigin.BOTH, sync = true)
    public void verify(
            @Param(description = "The dimension to verify", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "Search radius in chunks around the origin (larger is much slower)", defaultValue = "48")
            int radius
    ) {
        World world = resolveIrisWorld(dimension);
        if (world == null) {
            sender().sendMessage(C.RED + "No loaded Iris world found for " + dimension.getLoadKey() + ". Join or create one first (the search runs against a live world).");
            return;
        }
        boolean senderIsPlayer = sender() != null && sender().isPlayer();
        Location center = (senderIsPlayer && player().getWorld() == world) ? player().getLocation() : world.getSpawnLocation();
        int searchRadius = Math.max(1, Math.min(radius, 1000));

        sender().sendMessage(C.GREEN + "Verifying structures in " + C.WHITE + world.getName() + C.GREEN + " from " + center.getBlockX() + "," + center.getBlockZ() + " within " + searchRadius + " chunks...");

        Engine engine = null;
        PlatformChunkGenerator access = IrisToolbelt.access(world);
        if (access != null) {
            engine = access.getEngine();
        }
        Set<String> reachable = engine == null ? Collections.emptySet() : StructureReachability.reachableKeys(engine);

        int found = 0;
        int missing = 0;
        int unreachable = 0;
        int irisPlaced = 0;
        KList<String> notFound = new KList<>();
        KList<String> cannotGenerate = new KList<>();
        for (org.bukkit.generator.structure.Structure structure : Registry.STRUCTURE) {
            NamespacedKey key = structure.getKey();
            String keyName = key == null ? structure.toString() : key.toString();
            boolean isIrisPlaced = engine != null && IrisStructureLocator.suppressesVanilla(engine, keyName);
            boolean isReachable = engine != null && reachable.contains(keyName.toLowerCase());
            if (engine != null && !isIrisPlaced && !isReachable) {
                unreachable++;
                KList<String> miss = StructureReachability.missingBiomeKeys(engine, keyName);
                cannotGenerate.add(keyName + (miss.isEmpty() ? "" : " (needs " + String.join("/", miss) + ")"));
                continue;
            }
            if (isIrisPlaced) {
                irisPlaced++;
            }
            try {
                StructureSearchResult result = world.locateNearestStructure(center, structure, searchRadius, true);
                if (result != null && result.getLocation() != null) {
                    found++;
                    Location l = result.getLocation();
                    sender().sendMessage((isIrisPlaced ? C.AQUA + "[iris] " : C.GREEN + "[ok] ") + C.WHITE + keyName + C.GREEN + " @ " + l.getBlockX() + "," + l.getBlockZ());
                } else {
                    missing++;
                    notFound.add(keyName);
                }
            } catch (Throwable e) {
                missing++;
                notFound.add(keyName + " (error: " + e.getClass().getSimpleName() + ")");
            }
        }

        sender().sendMessage(C.GREEN + "Structure verify: " + C.WHITE + found + C.GREEN + " located (" + irisPlaced + " iris-placed), "
                + C.WHITE + unreachable + C.GREEN + " cannot generate here, "
                + C.WHITE + missing + C.GREEN + " reachable-but-not-found within " + searchRadius + " chunks.");
        if (!cannotGenerate.isEmpty()) {
            sender().sendMessage(C.RED + "Cannot generate (required biomes absent from this pack): " + C.GRAY + String.join(", ", cannotGenerate));
        }
        if (!notFound.isEmpty()) {
            sender().sendMessage(C.YELLOW + "Reachable but not found (rarer than radius): " + C.GRAY + String.join(", ", notFound));
        }
    }

    private World resolveIrisWorld(IrisDimension dimension) {
        if (sender() != null && sender().isPlayer() && IrisToolbelt.isIrisWorld(player().getWorld())) {
            return player().getWorld();
        }
        World fallback = null;
        for (World w : Bukkit.getWorlds()) {
            if (!IrisToolbelt.isIrisWorld(w)) {
                continue;
            }
            if (fallback == null) {
                fallback = w;
            }
            PlatformChunkGenerator gen = IrisToolbelt.access(w);
            if (gen != null && gen.getEngine() != null && gen.getEngine().getDimension() != null
                    && dimension.getLoadKey().equals(gen.getEngine().getDimension().getLoadKey())) {
                return w;
            }
        }
        return fallback;
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

}
