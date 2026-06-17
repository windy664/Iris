/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.modded;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModdedDimensionRegistryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String FILE_NAME = "iris-dimensions.json";

    private ModdedDimensionRegistryStore() {
    }

    public static List<PersistentDimension> load(MinecraftServer server) {
        Path file = storeFile(server);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray entries = root.optJSONArray("dimensions");
            if (entries == null) {
                return new ArrayList<>();
            }
            Map<String, PersistentDimension> deduplicated = new LinkedHashMap<>();
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                String id = entry.optString("id", null);
                String packKey = entry.optString("packKey", null);
                if (id == null || packKey == null) {
                    continue;
                }
                long seed = entry.optLong("seed", 0L);
                deduplicated.put(id, new PersistentDimension(id, packKey, seed));
            }
            return new ArrayList<>(deduplicated.values());
        } catch (RuntimeException | IOException e) {
            LOGGER.error("Iris persistent dimension registry at {} is invalid; ignoring it", file, e);
            return new ArrayList<>();
        }
    }

    public static synchronized void put(MinecraftServer server, PersistentDimension dimension) {
        Map<String, PersistentDimension> current = index(load(server));
        current.put(dimension.id(), dimension);
        write(server, new ArrayList<>(current.values()));
    }

    public static synchronized void remove(MinecraftServer server, String id) {
        Map<String, PersistentDimension> current = index(load(server));
        if (current.remove(id) != null) {
            write(server, new ArrayList<>(current.values()));
        }
    }

    private static Map<String, PersistentDimension> index(List<PersistentDimension> dimensions) {
        Map<String, PersistentDimension> map = new LinkedHashMap<>();
        for (PersistentDimension dimension : dimensions) {
            map.put(dimension.id(), dimension);
        }
        return map;
    }

    private static void write(MinecraftServer server, List<PersistentDimension> dimensions) {
        Path file = storeFile(server);
        JSONArray entries = new JSONArray();
        for (PersistentDimension dimension : dimensions) {
            JSONObject entry = new JSONObject();
            entry.put("id", dimension.id());
            entry.put("packKey", dimension.packKey());
            entry.put("seed", dimension.seed());
            entries.put(entry);
        }
        JSONObject root = new JSONObject();
        root.put("dimensions", entries);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, root.toString(2), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Iris failed to write persistent dimension registry at {}", file, e);
        }
    }

    private static Path storeFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("iris").resolve(FILE_NAME);
    }

    public record PersistentDimension(String id, String packKey, long seed) {
    }
}
