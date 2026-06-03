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
import art.arcane.volmlib.util.io.IO;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.util.Optional;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
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

    public static String deriveName(String key) {
        return key.toLowerCase().replace('/', '_').replace(':', '_');
    }

    public static Result importStructure(IrisData data, NamespacedKey key, String name, Mode mode) {
        return importStructure(data, key, name, mode, false);
    }

    public static Result importStructure(IrisData data, NamespacedKey key, String name, Mode mode, boolean objectOnly) {
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

        boolean exists = objectOnly ? objectFile.exists() : (objectFile.exists() || structureFile.exists());
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
            BlockData blockData = block.getBlockData();
            Material mat = blockData.getMaterial();
            if (mat == Material.STRUCTURE_VOID) {
                continue;
            }
            boolean structural = mat == Material.JIGSAW || mat == Material.STRUCTURE_BLOCK;
            if (mat == Material.JIGSAW) {
                BlockData resolved = readJigsawFinalState(block);
                if (resolved == null || isAir(resolved)) {
                    continue;
                }
                blockData = resolved;
            } else if (mat == Material.STRUCTURE_BLOCK) {
                continue;
            }
            object.setUnsigned(x, y, z, blockData);
            count++;
            if (!structural) {
                LegacyTileData tile = captureTile(block);
                if (tile != null) {
                    object.setUnsignedTile(x, y, z, tile);
                    tiles++;
                }
            }
        }

        try {
            objectFile.getParentFile().mkdirs();
            object.write(objectFile);
            writeJson(pieceFile, pieceJson(name));
            if (objectOnly) {
                IO.deleteUp(poolFile);
                IO.deleteUp(structureFile);
            } else {
                writeJson(poolFile, poolJson(name));
                writeJson(structureFile, structureJson(name, key.toString(), Math.max(w, d)));
            }
        } catch (Throwable e) {
            return new Result(false, "Failed writing import for '" + name + "': " + e.getMessage(), count);
        }

        return new Result(true, "Imported " + key + " as '" + name + "' (" + count + " blocks, " + tiles + " tiles, " + w + "x" + h + "x" + d + ")", count);
    }

    private static boolean isAir(BlockData data) {
        Material m = data.getMaterial();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    private static BlockData readJigsawFinalState(BlockState block) {
        try {
            Object nbt = block.getClass().getMethod("getSnapshotNBT").invoke(block);
            if (nbt == null) {
                return null;
            }
            Object res = nbt.getClass().getMethod("getString", String.class).invoke(nbt, "final_state");
            String finalState = null;
            if (res instanceof String s) {
                finalState = s;
            } else if (res instanceof Optional<?> o && o.isPresent()) {
                finalState = String.valueOf(o.get());
            }
            if (finalState == null || finalState.isBlank()) {
                return null;
            }
            return Bukkit.createBlockData(finalState);
        } catch (Throwable e) {
            return null;
        }
    }

    private static LegacyTileData captureTile(BlockState block) {
        try {
            return LegacyTileData.fromBukkit(block);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Result importTemplateGroup(IrisData data, String groupName, String vanillaSource, String prefix, Mode mode) {
        File objectsRoot = new File(data.getDataFolder(), "objects");
        File prefixDir = new File(objectsRoot, prefix);
        if (!prefixDir.isDirectory()) {
            return new Result(false, "No imported templates under objects/" + prefix + " for " + groupName, 0);
        }

        List<File> iobs = new ArrayList<>();
        collectIob(prefixDir, iobs);
        if (iobs.isEmpty()) {
            return new Result(false, "No .iob templates under objects/" + prefix + " for " + groupName, 0);
        }

        File structureFile = new File(data.getDataFolder(), "structures/" + groupName + ".json");
        if (mode == Mode.ADD_ONLY && structureFile.exists()) {
            return new Result(false, "Skipped (add-only): structure '" + groupName + "' already exists", 0);
        }

        String rootPath = objectsRoot.getAbsolutePath() + File.separator;
        List<String> pieceNames = new ArrayList<>();
        int maxSpan = 1;
        for (File iob : iobs) {
            String rel = iob.getAbsolutePath().substring(rootPath.length()).replace(File.separatorChar, '/');
            if (rel.endsWith(".iob")) {
                rel = rel.substring(0, rel.length() - ".iob".length());
            }
            pieceNames.add(rel);
            maxSpan = Math.max(maxSpan, readObjectSpan(iob));
        }

        try {
            for (String piece : pieceNames) {
                writeJson(new File(data.getDataFolder(), "jigsaw-pieces/" + piece + ".json"), pieceJson(piece));
            }
            writeJson(new File(data.getDataFolder(), "jigsaw-pools/" + groupName + ".json"), poolJsonMulti(pieceNames));
            writeJson(structureFile, structureJson(groupName, vanillaSource, maxSpan));
        } catch (Throwable e) {
            return new Result(false, "Failed writing group structure '" + groupName + "': " + e.getMessage(), 0);
        }

        return new Result(true, "Built structure '" + groupName + "' from " + pieceNames.size() + " template variants", pieceNames.size());
    }

    private static void collectIob(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectIob(f, out);
            } else if (f.getName().endsWith(".iob")) {
                out.add(f);
            }
        }
    }

    private static int readObjectSpan(File iob) {
        try (DataInputStream din = new DataInputStream(new BufferedInputStream(new FileInputStream(iob)))) {
            int w = din.readInt();
            din.readInt();
            int d = din.readInt();
            return Math.max(1, Math.max(w, d));
        } catch (Throwable e) {
            return 1;
        }
    }

    private static Map<String, Object> poolJsonMulti(List<String> pieceNames) {
        List<Object> pieces = new ArrayList<>();
        for (String name : pieceNames) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("piece", name);
            entry.put("weight", 1);
            pieces.add(entry);
        }
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("pieces", pieces);
        return pool;
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
