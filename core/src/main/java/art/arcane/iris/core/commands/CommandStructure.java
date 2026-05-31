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
import art.arcane.iris.core.structure.StructureModeHandler;
import art.arcane.iris.core.structure.VillageImporter;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.DirectorHelp;
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
            @Param(description = "overwrite | add-only | merge", defaultValue = "overwrite", customHandler = StructureModeHandler.class)
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
            @Param(description = "overwrite | add-only | merge", defaultValue = "overwrite", customHandler = StructureModeHandler.class)
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

    @Director(description = "Import EVERY registered structure - vanilla AND ingested datapacks - into a pack: jigsaw structures rebuilt as pool graphs, single-template structures imported as objects. Idempotent in add-only mode.", aliases = {"import-all", "ia"}, origin = DirectorOrigin.BOTH)
    public void importAllVanilla(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "overwrite | add-only | merge", defaultValue = "add-only", customHandler = StructureModeHandler.class)
            String mode,
            @Param(description = "Also import non-jigsaw single-template structures", name = "include-non-jigsaw", aliases = {"single", "nbt"}, defaultValue = "true")
            boolean includeNonJigsaw
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        BulkStructureImporter.Report report = BulkStructureImporter.importAllVanilla(data, StructureImporter.parseMode(mode), includeNonJigsaw, sender());
        if (report.imported() > 0) {
            sender().sendMessage(C.GRAY + "Reference imported structures from a biome/region/dimension structures list, or run /iris structure list " + dimension.getLoadKey() + " to see the refreshed index.");
        }
    }

    @Director(description = "Import EVERY vanilla structure TEMPLATE (the piece NBTs under minecraft:.../...) as editable Iris objects, including the non-jigsaw templates that import-all cannot reach. Idempotent in add-only mode.", aliases = {"import-templates", "it"}, origin = DirectorOrigin.BOTH)
    public void importTemplates(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "overwrite | add-only | merge", defaultValue = "add-only", customHandler = StructureModeHandler.class)
            String mode
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        BulkStructureImporter.Report report = BulkStructureImporter.importAllTemplates(data, StructureImporter.parseMode(mode), sender());
        if (report.imported() > 0) {
            sender().sendMessage(C.GRAY + "Reference imported templates from a biome/region/dimension structures list, or run /iris structure list " + dimension.getLoadKey() + " to see the refreshed index.");
        }
    }

    @Director(description = "Re-ingest EVERY registered structure - vanilla AND ingested datapacks - plus every jigsaw template from scratch (overwrite), regenerating all .iob objects so they pick up the latest jigsaw/structure-block conversion. Use this after updating Iris or after ingesting datapacks if imported structures show raw markers.", aliases = {"reimport", "ri"}, origin = DirectorOrigin.BOTH)
    public void reingest(
            @Param(description = "The dimension whose pack to re-ingest", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        sender().sendMessage(C.GREEN + "Re-ingesting all vanilla structures and jigsaws for " + C.WHITE + dimension.getLoadKey() + C.GREEN + " (overwrite)...");
        BulkStructureImporter.Report jigsaws = BulkStructureImporter.importAllVanilla(data, StructureImporter.Mode.OVERWRITE, true, sender());
        BulkStructureImporter.Report templates = BulkStructureImporter.importAllTemplates(data, StructureImporter.Mode.OVERWRITE, sender());
        BulkStructureImporter.Report groups = BulkStructureImporter.importTemplateGroups(data, StructureImporter.Mode.OVERWRITE, sender());
        int imported = jigsaws.imported() + templates.imported() + groups.imported();
        int failed = jigsaws.failed() + templates.failed() + groups.failed();
        sender().sendMessage(C.GREEN + "Re-ingest complete: " + C.WHITE + imported + C.GREEN + " objects rewritten, " + C.WHITE + failed + C.GREEN + " failed.");
        sender().sendMessage(C.GRAY + "Regenerate chunks (or use a fresh world) for structures to place from the rewritten objects. Run /iris structure list " + dimension.getLoadKey() + " to refresh the index.");
    }

    @Director(description = "Locate every vanilla/datapack/iris structure to verify which are locatable in this world. Heavy synchronous search per structure - keep the radius modest. 'Not found' can mean rarer than the radius, a different dimension (nether/end structures never appear in the overworld), or a structure that generates but cannot be located; generation itself happens during chunk decoration, independent of locate.", aliases = {"verify", "test", "locateall"}, origin = DirectorOrigin.BOTH, sync = true)
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

        int found = 0;
        int missing = 0;
        KList<String> notFound = new KList<>();
        for (org.bukkit.generator.structure.Structure structure : Registry.STRUCTURE) {
            NamespacedKey key = structure.getKey();
            String keyName = key == null ? structure.toString() : key.toString();
            try {
                StructureSearchResult result = world.locateNearestStructure(center, structure, searchRadius, true);
                if (result != null && result.getLocation() != null) {
                    found++;
                    Location l = result.getLocation();
                    sender().sendMessage(C.GREEN + "[ok] " + C.WHITE + keyName + C.GREEN + " @ " + l.getBlockX() + "," + l.getBlockZ());
                } else {
                    missing++;
                    notFound.add(keyName);
                }
            } catch (Throwable e) {
                missing++;
                notFound.add(keyName + " (error: " + e.getClass().getSimpleName() + ")");
            }
        }

        sender().sendMessage(C.GREEN + "Structure verify: " + C.WHITE + found + C.GREEN + " generate, " + C.WHITE + missing + C.GREEN + " not found within " + searchRadius + " chunks.");
        if (!notFound.isEmpty()) {
            sender().sendMessage(C.YELLOW + "Not found (rarer than radius, disabled, or non-generating): " + C.GRAY + String.join(", ", notFound));
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
