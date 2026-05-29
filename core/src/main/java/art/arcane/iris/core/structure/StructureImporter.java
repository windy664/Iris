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

package art.arcane.iris.core.structure;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.LegacyTileData;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StructureImporter {
    public enum Mode {
        OVERWRITE,
        ADD_ONLY,
        MERGE
    }

    public record Result(boolean success, String message, int blocks) {
    }

    private StructureImporter() {
    }

    public static Mode parseMode(String s) {
        if (s == null) {
            return Mode.OVERWRITE;
        }
        return switch (s.toLowerCase().replace('-', '_')) {
            case "add_only", "addonly", "add" -> Mode.ADD_ONLY;
            case "merge" -> Mode.MERGE;
            default -> Mode.OVERWRITE;
        };
    }

    public static String deriveName(NamespacedKey key) {
        return (key.getNamespace() + "_" + key.getKey()).replace('/', '_').replace(':', '_');
    }

    public static Result importStructure(IrisData data, NamespacedKey key, String name, Mode mode) {
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(key);
        } catch (Throwable e) {
            return new Result(false, "Failed to load structure " + key + ": " + e.getMessage(), 0);
        }
        if (structure == null || structure.getPalettes().isEmpty()) {
            return new Result(false, "No loadable structure NBT for key " + key + " (jigsaw structures must be imported by their piece keys)", 0);
        }

        BlockVector size = structure.getSize();
        int w = Math.max(1, size.getBlockX());
        int h = Math.max(1, size.getBlockY());
        int d = Math.max(1, size.getBlockZ());

        File objectFile = new File(data.getDataFolder(), "objects/" + name + ".iob");
        File pieceFile = new File(data.getDataFolder(), "jigsaw-pieces/" + name + ".json");
        File poolFile = new File(data.getDataFolder(), "jigsaw-pools/" + name + ".json");
        File structureFile = new File(data.getDataFolder(), "structures/" + name + ".json");

        boolean exists = objectFile.exists() || structureFile.exists();
        if (mode == Mode.ADD_ONLY && exists) {
            return new Result(false, "Skipped (add-only): '" + name + "' already exists", 0);
        }

        IrisObject object = new IrisObject(w, h, d);
        int count = 0;
        int tiles = 0;
        Palette palette = structure.getPalettes().get(0);
        for (BlockState block : palette.getBlocks()) {
            Location loc = block.getLocation();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) {
                continue;
            }
            object.setUnsigned(x, y, z, block.getBlockData());
            count++;
            LegacyTileData tile = captureTile(block);
            if (tile != null) {
                object.setUnsignedTile(x, y, z, tile);
                tiles++;
            }
        }

        try {
            objectFile.getParentFile().mkdirs();
            object.write(objectFile);
            writeJson(pieceFile, pieceJson(name));
            writeJson(poolFile, poolJson(name));
            writeJson(structureFile, structureJson(name, key.toString(), Math.max(w, d)));
        } catch (Throwable e) {
            return new Result(false, "Failed writing import for '" + name + "': " + e.getMessage(), count);
        }

        return new Result(true, "Imported " + key + " as '" + name + "' (" + count + " blocks, " + tiles + " tiles, " + w + "x" + h + "x" + d + ")", count);
    }

    private static LegacyTileData captureTile(BlockState block) {
        try {
            return LegacyTileData.fromBukkit(block);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Map<String, Object> pieceJson(String name) {
        Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("object", name);
        piece.put("connectors", new ArrayList<>());
        piece.put("rotatable", true);
        return piece;
    }

    private static Map<String, Object> poolJson(String name) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("piece", name);
        entry.put("weight", 1);
        List<Object> pieces = new ArrayList<>();
        pieces.add(entry);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("pieces", pieces);
        return pool;
    }

    private static Map<String, Object> structureJson(String name, String source, int maxSpan) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("startPool", name);
        root.put("maxDepth", 1);
        root.put("maxSizeChunks", Math.max(1, (maxSpan / 16) + 1));
        root.put("placeMode", "CENTER_HEIGHT");
        root.put("vanillaSource", source);
        return root;
    }

    private static void writeJson(File file, Map<String, Object> content) throws Exception {
        file.getParentFile().mkdirs();
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(content);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }
}
