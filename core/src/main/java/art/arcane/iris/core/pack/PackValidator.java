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

package art.arcane.iris.core.pack;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class PackValidator {
    private static final String TRASH_ROOT = ".iris-trash";
    private static final String DATAPACK_IMPORTS = "datapack-imports";
    private static final String EXTERNAL_DATAPACKS = "externaldatapacks";
    private static final String INTERNAL_DATAPACKS = "internaldatapacks";
    private static final String DATAPACKS_FOLDER = "datapacks";
    private static final String CACHE_FOLDER = "cache";
    private static final String OBJECTS_FOLDER = "objects";
    private static final String DIMENSIONS_FOLDER = "dimensions";
    private static final List<String> MANAGED_RESOURCE_FOLDERS = List.of(
            "biomes",
            "regions",
            "entities",
            "spawners",
            "loot",
            "generators",
            "expressions",
            "markers",
            "blocks",
            "mods"
    );
    private static final DateTimeFormatter TRASH_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PackValidator() {
    }

    public static PackValidationResult validate(File packFolder) {
        String packName = packFolder == null ? "<unknown>" : packFolder.getName();
        List<String> blockingErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> removedUnusedFiles = new ArrayList<>();
        long validatedAt = System.currentTimeMillis();

        if (packFolder == null || !packFolder.isDirectory()) {
            blockingErrors.add("Pack folder does not exist or is not a directory.");
            return new PackValidationResult(packName, blockingErrors, warnings, removedUnusedFiles, validatedAt);
        }

        File dimensionsFolder = new File(packFolder, DIMENSIONS_FOLDER);
        if (!dimensionsFolder.isDirectory()) {
            blockingErrors.add("Missing dimensions/ folder.");
            return new PackValidationResult(packName, blockingErrors, warnings, removedUnusedFiles, validatedAt);
        }

        File[] dimensionFiles = dimensionsFolder.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (dimensionFiles == null || dimensionFiles.length == 0) {
            blockingErrors.add("No dimension JSON files under dimensions/.");
            return new PackValidationResult(packName, blockingErrors, warnings, removedUnusedFiles, validatedAt);
        }

        validateDimensions(packFolder, dimensionFiles, blockingErrors, warnings);

        try {
            String packTextCorpus = buildPackTextCorpus(packFolder);
            runUnusedResourceGc(packFolder, packTextCorpus, removedUnusedFiles, warnings);
        } catch (Throwable e) {
            IrisLogging.reportError("PackValidator GC pass failed for pack '" + packName + "'", e);
            warnings.add("Unused-resource GC pass failed: " + e.getMessage());
        }

        return new PackValidationResult(packName, blockingErrors, warnings, removedUnusedFiles, validatedAt);
    }

    private static void validateDimensions(File packFolder, File[] dimensionFiles, List<String> blockingErrors, List<String> warnings) {
        File regionsFolder = new File(packFolder, "regions");
        File biomesFolder = new File(packFolder, "biomes");

        for (File dimFile : dimensionFiles) {
            String dimensionKey = stripExtension(dimFile.getName());
            JSONObject dimJson;
            try {
                dimJson = new JSONObject(Files.readString(dimFile.toPath(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                blockingErrors.add("Dimension '" + dimensionKey + "' has invalid JSON: " + e.getMessage());
                continue;
            }

            JSONArray regionsArray = dimJson.optJSONArray("regions");
            if (regionsArray == null || regionsArray.length() == 0) {
                blockingErrors.add("Dimension '" + dimensionKey + "' declares no regions.");
                continue;
            }

            int resolvedRegions = 0;
            for (int i = 0; i < regionsArray.length(); i++) {
                String regionKey = regionsArray.optString(i, null);
                if (regionKey == null || regionKey.isBlank()) {
                    warnings.add("Dimension '" + dimensionKey + "' has a blank region entry at index " + i + ".");
                    continue;
                }
                File regionFile = new File(regionsFolder, regionKey + ".json");
                if (!regionFile.isFile()) {
                    blockingErrors.add("Dimension '" + dimensionKey + "' references missing region '" + regionKey + "'.");
                    continue;
                }

                JSONObject regionJson;
                try {
                    regionJson = new JSONObject(Files.readString(regionFile.toPath(), StandardCharsets.UTF_8));
                } catch (Throwable e) {
                    blockingErrors.add("Region '" + regionKey + "' has invalid JSON: " + e.getMessage());
                    continue;
                }

                int anyBiome = countBiomeRefs(regionJson, "landBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "seaBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "shoreBiomes", biomesFolder, regionKey, warnings)
                        + countBiomeRefs(regionJson, "caveBiomes", biomesFolder, regionKey, warnings);
                if (anyBiome == 0) {
                    blockingErrors.add("Region '" + regionKey + "' has no resolvable biomes.");
                }
                resolvedRegions++;
            }

            if (resolvedRegions == 0) {
                blockingErrors.add("Dimension '" + dimensionKey + "' has no resolvable regions.");
            }
        }
    }

    private static int countBiomeRefs(JSONObject regionJson, String field, File biomesFolder, String regionKey, List<String> warnings) {
        JSONArray arr = regionJson.optJSONArray(field);
        if (arr == null) {
            return 0;
        }
        int resolved = 0;
        for (int i = 0; i < arr.length(); i++) {
            String biomeKey = arr.optString(i, null);
            if (biomeKey == null || biomeKey.isBlank()) {
                continue;
            }
            File biomeFile = new File(biomesFolder, biomeKey + ".json");
            if (!biomeFile.isFile()) {
                warnings.add("Region '" + regionKey + "' references missing biome '" + biomeKey + "' in " + field + ".");
                continue;
            }
            resolved++;
        }
        return resolved;
    }

    private static String buildPackTextCorpus(File packFolder) {
        StringBuilder sb = new StringBuilder(1 << 16);
        try (Stream<Path> stream = Files.walk(packFolder.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(PackValidator::isScannableJsonPath)
                    .forEach(p -> {
                        try {
                            sb.append(Files.readString(p, StandardCharsets.UTF_8));
                            sb.append('\n');
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable e) {
            IrisLogging.reportError("PackValidator failed to walk pack folder for corpus scan", e);
        }
        return sb.toString();
    }

    private static boolean isScannableJsonPath(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".json")) {
            return false;
        }
        String str = path.toString().replace(File.separatorChar, '/');
        if (str.contains("/" + TRASH_ROOT + "/")) {
            return false;
        }
        if (str.contains("/" + DATAPACK_IMPORTS + "/")) {
            return false;
        }
        if (str.contains("/" + EXTERNAL_DATAPACKS + "/")) {
            return false;
        }
        if (str.contains("/" + INTERNAL_DATAPACKS + "/")) {
            return false;
        }
        if (str.contains("/" + DATAPACKS_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/" + CACHE_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/" + OBJECTS_FOLDER + "/")) {
            return false;
        }
        if (str.contains("/.iris/")) {
            return false;
        }
        return true;
    }

    private static void runUnusedResourceGc(File packFolder, String corpus, List<String> removedUnusedFiles, List<String> warnings) {
        if (corpus == null || corpus.isEmpty()) {
            return;
        }
        File trashRoot = new File(packFolder, TRASH_ROOT + File.separator + LocalDateTime.now().format(TRASH_STAMP));
        Set<File> scheduledForTrash = new LinkedHashSet<>();

        for (String folderName : MANAGED_RESOURCE_FOLDERS) {
            File resourceFolder = new File(packFolder, folderName);
            if (!resourceFolder.isDirectory()) {
                continue;
            }

            List<File> files = listJsonRecursive(resourceFolder);
            for (File resourceFile : files) {
                String key = deriveKey(resourceFolder, resourceFile);
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (isReferenced(corpus, key)) {
                    continue;
                }
                scheduledForTrash.add(resourceFile);
            }
        }

        if (scheduledForTrash.isEmpty()) {
            return;
        }

        for (File file : scheduledForTrash) {
            try {
                Path src = file.toPath();
                Path relative = packFolder.toPath().relativize(src);
                Path dest = trashRoot.toPath().resolve(relative);
                Files.createDirectories(dest.getParent());
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                removedUnusedFiles.add(relative.toString().replace(File.separatorChar, '/'));
            } catch (Throwable e) {
                IrisLogging.reportError("PackValidator failed to move unused file " + file.getPath() + " to trash", e);
                warnings.add("Failed to quarantine unused file " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private static boolean isReferenced(String corpus, String key) {
        String needleQuoted = "\"" + key + "\"";
        if (corpus.contains(needleQuoted)) {
            return true;
        }
        int slash = key.indexOf('/');
        if (slash > 0) {
            String tail = key.substring(slash + 1);
            if (!tail.isBlank() && corpus.contains("\"" + tail + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static List<File> listJsonRecursive(File root) {
        List<File> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> out.add(p.toFile()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String deriveKey(File resourceFolder, File resourceFile) {
        Path relative = resourceFolder.toPath().relativize(resourceFile.toPath());
        String str = relative.toString().replace(File.separatorChar, '/');
        if (!str.endsWith(".json")) {
            return null;
        }
        return str.substring(0, str.length() - ".json".length());
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public static int restoreTrash(File packFolder) {
        if (packFolder == null || !packFolder.isDirectory()) {
            return 0;
        }
        File trashRoot = new File(packFolder, TRASH_ROOT);
        if (!trashRoot.isDirectory()) {
            return 0;
        }
        File[] dumps = trashRoot.listFiles(File::isDirectory);
        if (dumps == null || dumps.length == 0) {
            return 0;
        }
        Arrays.sort(dumps, Comparator.comparing(File::getName));
        File latestDump = dumps[dumps.length - 1];
        int restored = 0;
        try (Stream<Path> stream = Files.walk(latestDump.toPath())) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path src : files) {
                Path relative = latestDump.toPath().relativize(src);
                Path dest = packFolder.toPath().resolve(relative);
                Files.createDirectories(dest.getParent());
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                restored++;
            }
        } catch (Throwable e) {
            IrisLogging.reportError("PackValidator failed to restore trash for pack " + packFolder.getName(), e);
        }
        deleteFolderQuiet(latestDump);
        return restored;
    }

    private static void deleteFolderQuiet(File folder) {
        if (folder == null || !folder.exists()) {
            return;
        }
        try (Stream<Path> stream = Files.walk(folder.toPath())) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Throwable ignored) {
        }
    }

    public static Set<String> listReferencedKeysFromCorpus(String corpus) {
        Set<String> keys = new HashSet<>();
        if (corpus == null) {
            return keys;
        }
        int i = 0;
        while (i < corpus.length()) {
            int start = corpus.indexOf('"', i);
            if (start < 0) {
                break;
            }
            int end = corpus.indexOf('"', start + 1);
            if (end < 0) {
                break;
            }
            keys.add(corpus.substring(start + 1, end));
            i = end + 1;
        }
        return keys;
    }
}
