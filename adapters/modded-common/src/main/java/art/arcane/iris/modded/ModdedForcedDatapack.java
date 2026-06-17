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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.nms.datapack.IDataFixer;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisDimensionType;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ModdedForcedDatapack {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String PACK_ID = "iris_worldgen";
    private static final String PACK_FOLDER = "iris";
    private static final String STUDIO_POOL_TYPE_KEY = "studio_pool";
    private static final String STUDIO_POOL_TYPE_RESOURCE = "/data/irisworldgen/dimension_type/overworld.json";
    private static final int STUDIO_POOL_MIN_Y = -256;
    private static final int STUDIO_POOL_MAX_Y = 512;
    private static final Object LOCK = new Object();

    private ModdedForcedDatapack() {
    }

    public static RepositorySource repositorySource() {
        return (Consumer<Pack> consumer) -> {
            Pack pack = buildPack();
            if (pack != null) {
                consumer.accept(pack);
            }
        };
    }

    public static Path datapackRoot() {
        return ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("generated").resolve("datapack");
    }

    private static Path packDirectory() {
        return datapackRoot().resolve(PACK_FOLDER);
    }

    private static Pack buildPack() {
        Path directory = generate();
        if (directory == null) {
            return null;
        }
        PackLocationInfo location = new PackLocationInfo(
                PACK_ID,
                Component.literal("Iris World Generation"),
                PackSource.BUILT_IN,
                Optional.empty());
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, true);
        PathPackResources.PathResourcesSupplier supplier = new PathPackResources.PathResourcesSupplier(directory);
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, selection);
        if (pack == null) {
            LOGGER.error("Iris forced datapack at {} produced no readable pack metadata", directory);
        }
        return pack;
    }

    private static Path generate() {
        synchronized (LOCK) {
            try {
                return write();
            } catch (IOException e) {
                LOGGER.error("Iris failed to generate the forced startup datapack", e);
                return null;
            }
        }
    }

    private static Path write() throws IOException {
        Path packDirectory = packDirectory();
        clean(packDirectory);
        Files.createDirectories(packDirectory);

        File packFolder = packDirectory.toFile();
        KList<File> folders = new KList<>();
        folders.add(packFolder.getParentFile());
        KSet<String> seenBiomes = new KSet<>();
        IDataFixer fixer = DataVersion.getLatest().get();

        int packCount = 0;
        File[] packs = packsRoot().toFile().listFiles(File::isDirectory);
        if (packs != null) {
            for (File pack : packs) {
                if (installPack(pack, fixer, folders, seenBiomes)) {
                    packCount++;
                }
            }
        }

        writePackMeta(packDirectory);
        writeStudioPoolType(packDirectory);
        LOGGER.info("Iris forced startup datapack regenerated: {} pack(s), {} custom biome(s) at {}", packCount, seenBiomes.size(), packDirectory);
        return packDirectory;
    }

    private static boolean installPack(File packFolder, IDataFixer fixer, KList<File> folders, KSet<String> seenBiomes) {
        String packKey = packFolder.getName();
        if (!new File(packFolder, "dimensions/" + packKey + ".json").isFile()) {
            return false;
        }
        try {
            IrisData data = IrisData.get(packFolder);
            IrisDimension dimension = data.getDimensionLoader().load(packKey);
            if (dimension == null) {
                return false;
            }
            dimension.installBiomes(fixer, () -> data, folders, seenBiomes);
            writeDimensionType(folders, fixer, dimension);
            return true;
        } catch (Throwable e) {
            LOGGER.error("Iris failed to install forced datapack content for pack '{}'", packKey, e);
            return false;
        }
    }

    private static void writeDimensionType(KList<File> folders, IDataFixer fixer, IrisDimension dimension) throws IOException {
        if (fitsStudioPool(dimension)) {
            return;
        }
        IrisDimensionType type = dimension.getDimensionType();
        String json = type.toJson(fixer);
        String typeKey = dimension.getDimensionTypeKey();
        for (File parent : folders) {
            Path output = parent.toPath().resolve(PACK_FOLDER).resolve("data").resolve("irisworldgen").resolve("dimension_type").resolve(typeKey + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, json, StandardCharsets.UTF_8);
        }
    }

    private static boolean fitsStudioPool(IrisDimension dimension) {
        return dimension.getMinHeight() >= STUDIO_POOL_MIN_Y && dimension.getMaxHeight() <= STUDIO_POOL_MAX_Y;
    }

    private static void writeStudioPoolType(Path packDirectory) throws IOException {
        Path output = packDirectory.resolve("data").resolve("irisworldgen").resolve("dimension_type").resolve(STUDIO_POOL_TYPE_KEY + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, readStudioPoolType(), StandardCharsets.UTF_8);
    }

    private static String readStudioPoolType() throws IOException {
        try (InputStream stream = ModdedForcedDatapack.class.getResourceAsStream(STUDIO_POOL_TYPE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Bundled studio pool dimension type resource is missing: " + STUDIO_POOL_TYPE_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writePackMeta(Path packDirectory) throws IOException {
        int packFormat = DataVersion.getLatest().getPackFormat();
        String json = "{\n"
                + "  \"pack\": {\n"
                + "    \"description\": \"Iris world generation biomes and dimension types for installed packs.\",\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"min_format\": " + packFormat + ",\n"
                + "    \"max_format\": " + packFormat + "\n"
                + "  }\n"
                + "}\n";
        Files.writeString(packDirectory.resolve("pack.mcmeta"), json, StandardCharsets.UTF_8);
    }

    private static void clean(Path packDirectory) throws IOException {
        if (!Files.exists(packDirectory)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packDirectory)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(entries::add);
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }

    private static Path packsRoot() {
        return ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("packs");
    }
}
