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
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.collection.KList;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class BulkStructureImporter {
    public record Report(int total, int imported, int skipped, int failed) {
    }

    private BulkStructureImporter() {
    }

    public static Report importAllVanilla(IrisData data, StructureImporter.Mode mode, boolean includeNonJigsaw, VolmitSender sender) {
        KList<String> keys = INMS.get().getStructureKeys();
        List<String> vanilla = new ArrayList<>();
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                vanilla.add(k);
            }
        }
        Collections.sort(vanilla);

        int total = vanilla.size();
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        sender.sendMessage(C.GREEN + "Importing " + C.WHITE + total + C.GREEN + " vanilla & datapack structures (mode=" + mode + ", includeNonJigsaw=" + includeNonJigsaw + ")...");

        for (String keyString : vanilla) {
            NamespacedKey nk = NamespacedKey.fromString(keyString.toLowerCase());
            if (nk == null) {
                failed++;
                sender.sendMessage(C.RED + "[fail] " + keyString + ": invalid key");
                continue;
            }
            String name = StructureImporter.deriveName(nk);

            try {
                VillageImporter.Result jigsaw = VillageImporter.importVillage(data, nk, name, mode);
                if (jigsaw.success()) {
                    imported++;
                    sender.sendMessage(C.GRAY + "[jigsaw] " + keyString + " -> " + name);
                    continue;
                }

                String message = jigsaw.message() == null ? "" : jigsaw.message();
                if (message.contains("is not a jigsaw structure")) {
                    if (!includeNonJigsaw) {
                        skipped++;
                        continue;
                    }
                    StructureImporter.Result single = StructureImporter.importStructure(data, nk, name, mode);
                    if (single.success()) {
                        imported++;
                        sender.sendMessage(C.GRAY + "[single] " + keyString + " -> " + name);
                    } else if (single.message() != null && single.message().startsWith("Skipped")) {
                        skipped++;
                    } else if (single.message() != null && single.message().contains("No loadable structure NBT")) {
                        skipped++;
                        sender.sendMessage(C.YELLOW + "[skip] " + keyString + ": no single-template NBT - vanilla builds this in code or from separate piece templates (imported via the templates pass); nothing to import as one structure.");
                    } else {
                        failed++;
                        sender.sendMessage(C.RED + "[fail] " + keyString + ": " + single.message());
                    }
                    continue;
                }

                if (message.startsWith("Skipped")) {
                    skipped++;
                    continue;
                }

                failed++;
                sender.sendMessage(C.RED + "[fail] " + keyString + ": " + message);
            } catch (Throwable e) {
                failed++;
                sender.sendMessage(C.RED + "[fail] " + keyString + ": " + e.getMessage());
            }
        }

        StructureIndexService.write(data);

        sender.sendMessage(C.GREEN + "Bulk import complete: " + C.WHITE + imported + C.GREEN + " imported, " + C.WHITE + skipped + C.GREEN + " skipped, " + C.WHITE + failed + C.GREEN + " failed (" + C.WHITE + total + C.GREEN + " total).");
        return new Report(total, imported, skipped, failed);
    }

    public static Report importTemplateGroups(IrisData data, StructureImporter.Mode mode, VolmitSender sender) {
        String[][] groups = {
                {"shipwreck", "minecraft:shipwreck", "shipwreck"},
                {"ruined_portal", "minecraft:ruined_portal", "ruined_portal"},
                {"ocean_ruin", "minecraft:ocean_ruin_cold", "underwater_ruin"},
                {"nether_fossil", "minecraft:nether_fossil", "nether_fossils"},
        };

        int imported = 0;
        int skipped = 0;
        int failed = 0;

        sender.sendMessage(C.GREEN + "Building single-template structures from imported pieces (one variant placed per generation)...");
        for (String[] g : groups) {
            try {
                StructureImporter.Result result = StructureImporter.importTemplateGroup(data, g[0], g[1], g[2], mode);
                if (result.success()) {
                    imported++;
                    sender.sendMessage(C.GRAY + "[group] " + g[1] + " -> " + g[0] + " (" + result.blocks() + " variants)");
                } else if (result.message() != null && result.message().startsWith("Skipped")) {
                    skipped++;
                } else {
                    skipped++;
                    sender.sendMessage(C.YELLOW + "[skip] " + g[0] + ": " + result.message());
                }
            } catch (Throwable e) {
                failed++;
                sender.sendMessage(C.RED + "[fail] " + g[0] + ": " + e.getMessage());
            }
        }

        StructureIndexService.write(data);
        sender.sendMessage(C.GREEN + "Single-template structures: " + C.WHITE + imported + C.GREEN + " built, " + C.WHITE + skipped + C.GREEN + " skipped, " + C.WHITE + failed + C.GREEN + " failed.");
        return new Report(groups.length, imported, skipped, failed);
    }

    public static Report importAllTemplates(IrisData data, StructureImporter.Mode mode, VolmitSender sender) {
        List<String> templateKeys;
        try {
            templateKeys = enumerateTemplateKeys();
        } catch (Throwable e) {
            sender.sendMessage(C.RED + "Failed to enumerate vanilla structure templates via the server ResourceManager: " + e);
            return new Report(0, 0, 0, 0);
        }

        List<String> vanilla = new ArrayList<>();
        for (String key : templateKeys) {
            if (key != null && key.startsWith("minecraft:")) {
                vanilla.add(key);
            }
        }
        Collections.sort(vanilla);

        int total = vanilla.size();
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        if (total == 0) {
            sender.sendMessage(C.YELLOW + "No vanilla structure templates were found under the 'structure' resource path.");
            return new Report(0, 0, 0, 0);
        }

        sender.sendMessage(C.GREEN + "Importing " + C.WHITE + total + C.GREEN + " vanilla structure templates (mode=" + mode + ")...");

        for (String keyString : vanilla) {
            NamespacedKey nk = NamespacedKey.fromString(keyString.toLowerCase());
            if (nk == null) {
                failed++;
                sender.sendMessage(C.RED + "[fail] " + keyString + ": invalid key");
                continue;
            }
            String name = templateNameFor(keyString);

            try {
                StructureImporter.Result result = StructureImporter.importStructure(data, nk, name, mode, true);
                if (result.success()) {
                    imported++;
                } else if (result.message() != null && result.message().startsWith("Skipped")) {
                    skipped++;
                } else {
                    failed++;
                    sender.sendMessage(C.RED + "[fail] " + keyString + ": " + result.message());
                }
            } catch (Throwable e) {
                failed++;
                sender.sendMessage(C.RED + "[fail] " + keyString + ": " + e.getMessage());
            }

            int processed = imported + skipped + failed;
            if (processed % 50 == 0) {
                sender.sendMessage(C.GRAY + "..." + processed + "/" + total + " (" + imported + " imported, " + skipped + " skipped, " + failed + " failed)");
            }
        }

        StructureIndexService.write(data);

        sender.sendMessage(C.GREEN + "Template import complete: " + C.WHITE + imported + C.GREEN + " imported, " + C.WHITE + skipped + C.GREEN + " skipped, " + C.WHITE + failed + C.GREEN + " failed (" + C.WHITE + total + C.GREEN + " total).");
        return new Report(total, imported, skipped, failed);
    }

    private static String templateNameFor(String key) {
        int colon = key.indexOf(':');
        String namespace = colon >= 0 ? key.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? key.substring(colon + 1) : key;
        String safePath = path.toLowerCase().replaceAll("[^a-z0-9_/-]", "_");
        while (safePath.contains("//")) {
            safePath = safePath.replace("//", "/");
        }
        if (safePath.startsWith("/")) {
            safePath = safePath.substring(1);
        }
        return "minecraft".equals(namespace) ? safePath : namespace + "/" + safePath;
    }

    private static List<String> enumerateTemplateKeys() throws Exception {
        Object craftServer = Bukkit.getServer();
        Object dedicated = invoke(craftServer, "getHandle");
        Object server = invoke(dedicated, "getServer");
        Object resourceManager = resolveResourceManager(server);
        if (resourceManager == null) {
            throw new IllegalStateException("Could not resolve the server ResourceManager via reflection");
        }

        Method listResources = null;
        for (Method m : resourceManager.getClass().getMethods()) {
            if (m.getName().equals("listResources") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class
                    && m.getParameterTypes()[1] == Predicate.class
                    && Map.class.isAssignableFrom(m.getReturnType())) {
                listResources = m;
                break;
            }
        }
        if (listResources == null) {
            throw new NoSuchMethodException("listResources(String, Predicate) on " + resourceManager.getClass().getName());
        }
        listResources.setAccessible(true);

        Predicate<Object> endsWithNbt = location -> {
            String path = identifierPath(location);
            return path != null && path.endsWith(".nbt");
        };

        Object resultMap = listResources.invoke(resourceManager, "structure", endsWithNbt);
        TreeSet<String> keys = new TreeSet<>();
        if (resultMap instanceof Map<?, ?> map) {
            for (Object location : map.keySet()) {
                String namespace = identifierNamespace(location);
                String path = identifierPath(location);
                if (namespace == null || path == null) {
                    continue;
                }
                String stripped = path;
                if (stripped.startsWith("structure/")) {
                    stripped = stripped.substring("structure/".length());
                }
                if (stripped.endsWith(".nbt")) {
                    stripped = stripped.substring(0, stripped.length() - ".nbt".length());
                }
                if (stripped.isEmpty()) {
                    continue;
                }
                keys.add(namespace + ":" + stripped);
            }
        }
        return new ArrayList<>(keys);
    }

    private static Object resolveResourceManager(Object server) {
        try {
            Class<?> resourceManagerClass = Class.forName("net.minecraft.server.packs.resources.ResourceManager");
            Method getter = null;
            for (Method m : server.getClass().getMethods()) {
                if (m.getName().equals("getResourceManager") && m.getParameterCount() == 0
                        && resourceManagerClass.isAssignableFrom(m.getReturnType())) {
                    getter = m;
                    break;
                }
            }
            if (getter == null) {
                for (Method m : server.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && resourceManagerClass.isAssignableFrom(m.getReturnType())) {
                        getter = m;
                        break;
                    }
                }
            }
            if (getter == null) {
                return null;
            }
            getter.setAccessible(true);
            return getter.invoke(server);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String identifierNamespace(Object location) {
        try {
            Method m = location.getClass().getMethod("getNamespace");
            m.setAccessible(true);
            Object value = m.invoke(location);
            return value == null ? null : value.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String identifierPath(Object location) {
        try {
            Method m = location.getClass().getMethod("getPath");
            m.setAccessible(true);
            Object value = m.invoke(location);
            return value == null ? null : value.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m.invoke(target);
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m.invoke(target);
            }
        }
        throw new NoSuchMethodException(method + " on " + target.getClass().getName());
    }
}
