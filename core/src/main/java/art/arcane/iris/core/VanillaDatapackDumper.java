package art.arcane.iris.core;

import art.arcane.iris.Iris;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class VanillaDatapackDumper {
    private static final String DUMP_ZIP_NAME = "00-iris-vanilla-worldgen.zip";
    private static final String MARKER_FILE = "vanilla-datapack-version.txt";
    private static final String PACK_DESCRIPTION = "Iris extracted vanilla worldgen datapack.";

    private VanillaDatapackDumper() {
    }

    public static void dumpIfNeeded(KList<File> datapackFolders) {
        if (datapackFolders == null || datapackFolders.isEmpty()) {
            return;
        }

        String currentVersion = resolveVersionKey();
        if (currentVersion == null) {
            Iris.warn("Unable to determine server version for vanilla datapack dump.");
            return;
        }

        boolean needsDump = false;
        for (File folder : datapackFolders) {
            File zip = new File(folder, DUMP_ZIP_NAME);
            File marker = new File(folder, MARKER_FILE);
            if (!zip.exists() || !marker.exists() || !currentVersion.equals(readMarker(marker))) {
                needsDump = true;
                break;
            }
        }

        if (!needsDump) {
            Iris.verbose("Vanilla datapack is up to date, skipping dump.");
            return;
        }

        Iris.info("Dumping vanilla worldgen datapack...");
        Map<String, byte[]> entries = INMS.get().extractVanillaDatapack();
        if (entries.isEmpty()) {
            Iris.warn("Vanilla datapack extraction returned no entries. Skipping dump.");
            return;
        }

        byte[] zipBytes = buildZip(entries);
        if (zipBytes == null) {
            Iris.error("Failed to build vanilla datapack ZIP.");
            return;
        }

        int written = 0;
        for (File folder : datapackFolders) {
            folder.mkdirs();
            File zip = new File(folder, DUMP_ZIP_NAME);
            File marker = new File(folder, MARKER_FILE);
            try {
                Files.write(zip.toPath(), zipBytes);
                Files.writeString(marker.toPath(), currentVersion, StandardCharsets.UTF_8);
                written++;
            } catch (IOException e) {
                Iris.error("Failed to write vanilla datapack to " + folder.getAbsolutePath());
                e.printStackTrace();
            }
        }

        Iris.info("Vanilla datapack written to " + written + " world(s) with " + entries.size() + " entries.");
    }

    public static void removeIfPresent(KList<File> datapackFolders) {
        if (datapackFolders == null || datapackFolders.isEmpty()) {
            return;
        }

        int removed = 0;
        for (File folder : datapackFolders) {
            File zip = new File(folder, DUMP_ZIP_NAME);
            File marker = new File(folder, MARKER_FILE);
            if (zip.exists() && zip.delete()) {
                removed++;
            }
            if (marker.exists()) {
                marker.delete();
            }
        }

        if (removed > 0) {
            Iris.info("Removed vanilla datapack from " + removed + " world(s) (vanillaStructures disabled).");
        }
    }

    private static byte[] buildZip(Map<String, byte[]> entries) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("pack.mcmeta"));
                zos.write(buildPackMeta());
                zos.closeEntry();

                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            Iris.error("Failed to build vanilla datapack ZIP");
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] buildPackMeta() {
        int packFormat = INMS.get().getDataVersion().getPackFormat();
        JSONObject root = new JSONObject();
        JSONObject pack = new JSONObject();
        pack.put("description", PACK_DESCRIPTION);
        pack.put("pack_format", packFormat);
        root.put("pack", pack);
        return root.toString(4).getBytes(StandardCharsets.UTF_8);
    }

    private static String resolveVersionKey() {
        try {
            DataVersion dv = INMS.get().getDataVersion();
            return dv.getVersion() + ":" + dv.getPackFormat();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readMarker(File marker) {
        try {
            return Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
