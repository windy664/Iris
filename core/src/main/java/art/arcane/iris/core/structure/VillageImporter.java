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
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VillageImporter {
    public record Result(boolean success, String message, int pools, int pieces) {
    }

    private VillageImporter() {
    }

    public static Result importVillage(IrisData data, NamespacedKey structureKey, String name, StructureImporter.Mode mode) {
        Object server;
        Object registryAccess;
        Object structureManager;
        try {
            Object craftServer = Bukkit.getServer();
            Object dedicated = invoke(craftServer, "getHandle");
            server = invoke(dedicated, "getServer");
            registryAccess = resolveRegistryAccess(server);
            structureManager = invoke(server, "getStructureManager");
        } catch (Throwable e) {
            return new Result(false, "Failed to access server registries via reflection: " + e, 0, 0);
        }
        if (registryAccess == null) {
            return new Result(false, "Could not resolve RegistryAccess from the server", 0, 0);
        }

        Object startPool;
        int maxDepth;
        try {
            Object structureRegistry = lookupRegistry(registryAccess, "STRUCTURE");
            Object structure = registryGet(structureRegistry, structureKey);
            if (structure == null) {
                return new Result(false, "No structure registered for key " + structureKey, 0, 0);
            }
            if (!structure.getClass().getName().endsWith("JigsawStructure")) {
                return new Result(false, "Structure " + structureKey + " is not a jigsaw structure (" + structure.getClass().getSimpleName() + "); use 'import' for single-template structures", 0, 0);
            }
            Object startPoolHolder = invoke(structure, "getStartPool");
            startPool = unwrapHolder(startPoolHolder);
            maxDepth = readIntField(structure, "maxDepth", 7);
        } catch (Throwable e) {
            return new Result(false, "Failed to read jigsaw structure graph: " + e, 0, 0);
        }

        Object templatePoolRegistry;
        java.util.Random random;
        try {
            templatePoolRegistry = lookupRegistry(registryAccess, "TEMPLATE_POOL");
            random = new java.util.Random(structureKey.hashCode());
        } catch (Throwable e) {
            return new Result(false, "Failed to access TEMPLATE_POOL registry: " + e, 0, 0);
        }

        String startPoolKey;
        try {
            startPoolKey = registryKeyOf(templatePoolRegistry, startPool);
        } catch (Throwable e) {
            startPoolKey = null;
        }
        if (startPoolKey == null) {
            return new Result(false, "Could not resolve the start pool key for " + structureKey, 0, 0);
        }

        Set<String> visitedPools = new HashSet<>();
        Set<String> importedPieces = new HashSet<>();
        Deque<String> poolQueue = new ArrayDeque<>();
        poolQueue.add(startPoolKey);

        Map<String, Map<String, Object>> emittedPools = new LinkedHashMap<>();
        Map<String, Map<String, Object>> emittedPieces = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int pieceBlocks = 0;

        while (!poolQueue.isEmpty()) {
            String poolKey = poolQueue.poll();
            if (!visitedPools.add(poolKey)) {
                continue;
            }
            Object pool;
            try {
                pool = registryGetByKey(templatePoolRegistry, poolKey);
            } catch (Throwable e) {
                errors.add("pool " + poolKey + ": " + e.getMessage());
                continue;
            }
            if (pool == null) {
                continue;
            }

            String irisPoolName = poolName(name, poolKey);
            List<Object> pieceEntries = new ArrayList<>();

            String fallbackKey = null;
            try {
                Object fallbackHolder = invoke(pool, "getFallback");
                Object fallbackPool = unwrapHolder(fallbackHolder);
                fallbackKey = registryKeyOf(templatePoolRegistry, fallbackPool);
            } catch (Throwable ignored) {
            }
            if (fallbackKey != null && !fallbackKey.equals(poolKey)) {
                poolQueue.add(fallbackKey);
            }

            List<?> templates;
            try {
                templates = (List<?>) invoke(pool, "getTemplates");
            } catch (Throwable e) {
                errors.add("templates " + poolKey + ": " + e.getMessage());
                templates = List.of();
            }

            for (Object pair : templates) {
                Object element;
                int weight;
                try {
                    element = invoke(pair, "getFirst");
                    Object second = invoke(pair, "getSecond");
                    weight = second instanceof Number ? Math.max(1, ((Number) second).intValue()) : 1;
                } catch (Throwable e) {
                    continue;
                }
                if (element == null) {
                    continue;
                }
                String templateLocation = templateLocationOf(element);
                if (templateLocation == null) {
                    continue;
                }
                NamespacedKey pieceNbtKey = NamespacedKey.fromString(templateLocation.toLowerCase());
                if (pieceNbtKey == null) {
                    errors.add("bad piece key " + templateLocation);
                    continue;
                }
                String irisPieceName = pieceName(name, templateLocation);

                if (importedPieces.add(irisPieceName)) {
                    StructureImporter.Result imported = StructureImporter.importStructure(data, pieceNbtKey, irisPieceName, mode);
                    if (!imported.success()) {
                        errors.add(templateLocation + ": " + imported.message());
                        continue;
                    }
                    pieceBlocks += imported.blocks();
                    removeStrayPieceArtifacts(data, irisPieceName);

                    Connectors result = readConnectors(element, structureManager, random, name);
                    emittedPieces.put(irisPieceName, pieceJson(irisPieceName, result.json()));
                    poolQueue.addAll(result.targetPoolKeys());
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("piece", irisPieceName);
                entry.put("weight", weight);
                pieceEntries.add(entry);
            }

            Map<String, Object> poolJson = new LinkedHashMap<>();
            poolJson.put("pieces", pieceEntries);
            if (fallbackKey != null && !fallbackKey.equals(poolKey)) {
                poolJson.put("fallback", poolName(name, fallbackKey));
            }
            emittedPools.put(irisPoolName, poolJson);
        }

        if (emittedPieces.isEmpty()) {
            return new Result(false, "Imported 0 pieces for " + structureKey + (errors.isEmpty() ? "" : " (" + errors.get(0) + ")"), 0, 0);
        }

        try {
            for (Map.Entry<String, Map<String, Object>> e : emittedPieces.entrySet()) {
                writeJson(new File(data.getDataFolder(), "jigsaw-pieces/" + e.getKey() + ".json"), e.getValue());
            }
            for (Map.Entry<String, Map<String, Object>> e : emittedPools.entrySet()) {
                writeJson(new File(data.getDataFolder(), "jigsaw-pools/" + e.getKey() + ".json"), e.getValue());
            }
            writeJson(new File(data.getDataFolder(), "structures/" + name + ".json"), structureJson(name, structureKey.toString(), poolName(name, startPoolKey), maxDepth));
        } catch (Throwable e) {
            return new Result(false, "Failed writing jigsaw resources for '" + name + "': " + e, emittedPools.size(), emittedPieces.size());
        }

        String msg = "Imported village " + structureKey + " as '" + name + "': " + emittedPieces.size() + " pieces, " + emittedPools.size() + " pools, " + pieceBlocks + " blocks";
        if (!errors.isEmpty()) {
            msg += " (" + errors.size() + " piece(s) skipped; first: " + errors.get(0) + ")";
        }
        return new Result(true, msg, emittedPools.size(), emittedPieces.size());
    }

    private record Connectors(List<Map<String, Object>> json, Set<String> targetPoolKeys) {
    }

    private static Connectors readConnectors(Object element, Object structureManager, java.util.Random random, String baseName) {
        List<Map<String, Object>> connectors = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        try {
            Object zero = staticField("net.minecraft.core.BlockPos", "ZERO");
            Object rotationNone = staticField("net.minecraft.world.level.block.Rotation", "NONE");
            Method m = findMethod4(element.getClass(), "getShuffledJigsawBlocks");
            if (m == null) {
                return new Connectors(connectors, targets);
            }
            m.setAccessible(true);
            Object random0 = freshRandomSource(random);
            List<?> blocks = (List<?>) m.invoke(element, structureManager, zero, rotationNone, random0);
            if (blocks == null) {
                return new Connectors(connectors, targets);
            }
            for (Object jigsaw : blocks) {
                String[] rawPoolKey = new String[1];
                Map<String, Object> connector = connectorFrom(jigsaw, baseName, rawPoolKey);
                if (connector != null) {
                    connectors.add(connector);
                    if (rawPoolKey[0] != null && !rawPoolKey[0].isEmpty()) {
                        targets.add(rawPoolKey[0]);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new Connectors(connectors, targets);
    }

    private static Map<String, Object> connectorFrom(Object jigsaw, String baseName, String[] rawPoolKeyOut) {
        try {
            Object info = invoke(jigsaw, "info");
            Object pos = invoke(info, "pos");
            Object blockState = invoke(info, "state");
            int x = readInt(pos, "getX");
            int y = readInt(pos, "getY");
            int z = readInt(pos, "getZ");

            Object poolKey = invoke(jigsaw, "pool");
            String poolId = identifierString(invoke(poolKey, "identifier"));
            Object nameId = invoke(jigsaw, "name");
            Object targetId = invoke(jigsaw, "target");
            Object jointType = invoke(jigsaw, "jointType");

            String front = frontFacing(blockState);
            rawPoolKeyOut[0] = poolId;

            Map<String, Object> connector = new LinkedHashMap<>();
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("x", x);
            position.put("y", y);
            position.put("z", z);
            connector.put("position", position);
            connector.put("direction", irisDirection(front));
            connector.put("pool", poolId == null ? "" : poolName(baseName, poolId));
            connector.put("name", identifierString(nameId));
            connector.put("targetName", identifierString(targetId));
            connector.put("joint", jointType != null && jointType.toString().toUpperCase().contains("ALIGN") ? "ALIGNED" : "ROLLABLE");
            return connector;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String frontFacing(Object blockState) {
        try {
            Class<?> jigsawBlock = Class.forName("net.minecraft.world.level.block.JigsawBlock");
            Method getFront = jigsawBlock.getMethod("getFrontFacing", Class.forName("net.minecraft.world.level.block.state.BlockState"));
            Object direction = getFront.invoke(null, blockState);
            if (direction == null) {
                return "north";
            }
            Method getName = direction.getClass().getMethod("getName");
            getName.setAccessible(true);
            return String.valueOf(getName.invoke(direction)).toLowerCase();
        } catch (Throwable e) {
            return "north";
        }
    }

    private static String irisDirection(String front) {
        return switch (front) {
            case "up" -> "UP_POSITIVE_Y";
            case "down" -> "DOWN_NEGATIVE_Y";
            case "south" -> "SOUTH_POSITIVE_Z";
            case "east" -> "EAST_POSITIVE_X";
            case "west" -> "WEST_NEGATIVE_X";
            default -> "NORTH_NEGATIVE_Z";
        };
    }

    private static String templateLocationOf(Object element) {
        try {
            Method m = findMethod(element.getClass(), "getTemplateLocation");
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            Object id = m.invoke(element);
            return identifierString(id);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object resolveRegistryAccess(Object server) {
        try {
            Class<?> frozen = Class.forName("net.minecraft.core.RegistryAccess$Frozen");
            for (Method m : server.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && frozen.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object o = m.invoke(server);
                    if (o != null) {
                        return o;
                    }
                }
            }
            Class<?> ra = Class.forName("net.minecraft.core.RegistryAccess");
            for (Method m : server.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && ra.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object o = m.invoke(server);
                    if (o != null) {
                        return o;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object lookupRegistry(Object registryAccess, String registryName) throws Exception {
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        Object resourceKey = registries.getField(registryName).get(null);
        Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
        Method registryOverload = null;
        for (Method m : registryAccess.getClass().getMethods()) {
            if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].getName().endsWith("ResourceKey")
                    && registryClass.isAssignableFrom(m.getReturnType())) {
                registryOverload = m;
                break;
            }
        }
        if (registryOverload == null) {
            for (Method m : registryAccess.getClass().getMethods()) {
                if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().endsWith("ResourceKey")) {
                    registryOverload = m;
                    break;
                }
            }
        }
        if (registryOverload == null) {
            throw new NoSuchMethodException("lookupOrThrow(ResourceKey) on " + registryAccess.getClass().getName());
        }
        registryOverload.setAccessible(true);
        return registryOverload.invoke(registryAccess, resourceKey);
    }

    private static Object registryGet(Object registry, NamespacedKey key) throws Exception {
        Object id = identifierOf(key);
        for (Method m : registry.getClass().getMethods()) {
            if (m.getName().equals("getValue") && m.getParameterCount() == 1 && m.getParameterTypes()[0].getName().endsWith("Identifier")) {
                m.setAccessible(true);
                return m.invoke(registry, id);
            }
        }
        for (Method m : registry.getClass().getMethods()) {
            if (m.getName().equals("getOptional") && m.getParameterCount() == 1 && m.getParameterTypes()[0].getName().endsWith("Identifier")) {
                m.setAccessible(true);
                return unwrapOptional(m.invoke(registry, id));
            }
        }
        return null;
    }

    private static Object registryGetByKey(Object registry, String key) throws Exception {
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase());
        if (nk == null) {
            return null;
        }
        return registryGet(registry, nk);
    }

    private static String registryKeyOf(Object registry, Object value) throws Exception {
        if (value == null) {
            return null;
        }
        for (Method m : registry.getClass().getMethods()) {
            if (m.getName().equals("getKey") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                Object id = m.invoke(registry, value);
                String s = identifierString(id);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Object identifierOf(NamespacedKey key) throws Exception {
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");
        try {
            Method fromNamespaceAndPath = identifier.getMethod("fromNamespaceAndPath", String.class, String.class);
            return fromNamespaceAndPath.invoke(null, key.getNamespace(), key.getKey());
        } catch (NoSuchMethodException e) {
            Method withDefaultNamespace = identifier.getMethod("parse", String.class);
            return withDefaultNamespace.invoke(null, key.toString());
        }
    }

    private static String identifierString(Object id) {
        if (id == null) {
            return null;
        }
        try {
            Method getNamespace = id.getClass().getMethod("getNamespace");
            Method getPath = id.getClass().getMethod("getPath");
            getNamespace.setAccessible(true);
            getPath.setAccessible(true);
            return getNamespace.invoke(id) + ":" + getPath.invoke(id);
        } catch (Throwable e) {
            return id.toString();
        }
    }

    private static Object unwrapHolder(Object holder) {
        if (holder == null) {
            return null;
        }
        try {
            Method value = findMethod(holder.getClass(), "value");
            if (value != null) {
                value.setAccessible(true);
                return value.invoke(holder);
            }
        } catch (Throwable ignored) {
        }
        return holder;
    }

    private static Object unwrapOptional(Object opt) {
        if (opt == null) {
            return null;
        }
        if (opt instanceof java.util.Optional<?> o) {
            return o.orElse(null);
        }
        return opt;
    }

    private static int readIntField(Object o, String fieldName, int fallback) {
        try {
            Field f = o.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static int readInt(Object o, String method) throws Exception {
        Method m = o.getClass().getMethod(method);
        m.setAccessible(true);
        return ((Number) m.invoke(o)).intValue();
    }

    private static Object staticField(String className, String fieldName) throws Exception {
        Class<?> c = Class.forName(className);
        Field f = c.getField(fieldName);
        return f.get(null);
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = findMethod(target.getClass(), method);
        if (m == null) {
            throw new NoSuchMethodException(method + " on " + target.getClass().getName());
        }
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    private static Method findMethod4(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 4) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 4) {
                return m;
            }
        }
        return null;
    }

    private static Object freshRandomSource(java.util.Random random) throws Exception {
        Class<?> randomSource = Class.forName("net.minecraft.util.RandomSource");
        Method create = randomSource.getMethod("create", long.class);
        return create.invoke(null, random.nextLong());
    }

    private static String poolName(String base, String poolKey) {
        return base + "/pool/" + sanitize(poolKey);
    }

    private static String pieceName(String base, String templateLocation) {
        return base + "/piece/" + sanitize(templateLocation);
    }

    private static String sanitize(String key) {
        return key.replace(':', '_').replace('/', '_');
    }

    private static Map<String, Object> pieceJson(String pieceName, List<Map<String, Object>> connectors) {
        Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("object", pieceName);
        piece.put("connectors", connectors);
        piece.put("rotatable", true);
        return piece;
    }

    private static Map<String, Object> structureJson(String name, String source, String startPool, int maxDepth) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("startPool", startPool);
        root.put("maxDepth", Math.max(1, Math.min(30, maxDepth)));
        root.put("maxSizeChunks", 8);
        root.put("placeMode", "STRUCTURE_PIECE");
        root.put("vanillaSource", source);
        return root;
    }

    private static void removeStrayPieceArtifacts(IrisData data, String pieceName) {
        new File(data.getDataFolder(), "jigsaw-pools/" + pieceName + ".json").delete();
        new File(data.getDataFolder(), "structures/" + pieceName + ".json").delete();
    }

    private static void writeJson(File file, Map<String, Object> content) throws Exception {
        file.getParentFile().mkdirs();
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(content);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }
}
