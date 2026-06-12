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
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModdedWorldEngines {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final ConcurrentHashMap<ServerLevel, Engine> ENGINES = new ConcurrentHashMap<>();

    private ModdedWorldEngines() {
    }

    public static Engine get(ServerLevel level, String dimensionKey) {
        Engine existing = ENGINES.get(level);
        if (existing != null) {
            return existing;
        }
        return ENGINES.computeIfAbsent(level, (ServerLevel l) -> create(l, dimensionKey));
    }

    private static Engine create(ServerLevel level, String dimensionKey) {
        ModdedEngineBootstrap.bind();
        File pack = resolvePack(dimensionKey);
        IrisData data = IrisData.get(pack);
        IrisDimension dimension = data.getDimensionLoader().load(dimensionKey);
        if (dimension == null) {
            LOGGER.error("Iris pack at {} does not contain dimension '{}' (expected dimensions/{}.json). Install a matching Iris pack and restart.",
                    pack.getAbsolutePath(), dimensionKey, dimensionKey);
            throw new IllegalStateException("Iris dimension '" + dimensionKey + "' missing from pack " + pack.getAbsolutePath());
        }

        long seed = level.getSeed();
        File worldFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).toFile();
        IrisWorld world = IrisWorld.builder()
                .name(level.dimension().identifier().toString().replace(':', '_'))
                .seed(seed)
                .worldFolder(worldFolder)
                .minHeight(dimension.getMinHeight())
                .maxHeight(dimension.getMaxHeight())
                .build();
        Engine engine = new IrisEngine(new EngineTarget(world, dimension, data), false);

        int levelMinY = level.getMinY();
        int levelMaxY = level.getMinY() + level.getHeight();
        if (dimension.getMinHeight() != levelMinY || dimension.getMaxHeight() != levelMaxY) {
            LOGGER.error("Iris pack height mismatch for {}: pack generates {}..{} but the level is {}..{}. Terrain outside the level range will be clipped; ship a matching dimension_type.",
                    level.dimension().identifier(), dimension.getMinHeight(), dimension.getMaxHeight(), levelMinY, levelMaxY);
        }

        LOGGER.info("Iris engine up for {}: pack={} dim={} seed={} height={}..{}",
                level.dimension().identifier(), pack.getAbsolutePath(), dimension.getLoadKey(), seed, dimension.getMinHeight(), dimension.getMaxHeight());
        return engine;
    }

    private static File resolvePack(String dimensionKey) {
        File pack = ModdedEngineBootstrap.loader().configDir()
                .resolve("irisworldgen")
                .resolve("packs")
                .resolve(dimensionKey)
                .toFile();
        if (pack.isDirectory()) {
            return pack;
        }

        String parity = System.getProperty("iris.parity");
        if (parity != null) {
            String parityPath = parity;
            int lastColon = parity.lastIndexOf(':');
            if (lastColon > 0) {
                try {
                    Integer.parseInt(parity.substring(lastColon + 1));
                    parityPath = parity.substring(0, lastColon);
                } catch (NumberFormatException ignored) {
                }
            }
            File parityPack = new File(parityPath);
            if (parityPack.isDirectory()) {
                LOGGER.warn("Iris pack missing at {}; falling back to parity pack {}", pack.getAbsolutePath(), parityPack.getAbsolutePath());
                return parityPack;
            }
        }

        LOGGER.error("===============================================================");
        LOGGER.error("Iris pack for dimension '{}' is not installed.", dimensionKey);
        LOGGER.error("Expected a pack folder at: {}", pack.getAbsolutePath());
        LOGGER.error("Install an Iris pack there (the folder must contain dimensions/{}.json) and restart the server.", dimensionKey);
        LOGGER.error("===============================================================");
        throw new IllegalStateException("Iris pack not installed: " + pack.getAbsolutePath());
    }

    public static void shutdown() {
        for (Map.Entry<ServerLevel, Engine> entry : ENGINES.entrySet()) {
            Engine engine = entry.getValue();
            try {
                if (!engine.isClosed()) {
                    engine.close();
                }
                LOGGER.info("Iris engine closed for {}", entry.getKey().dimension().identifier());
            } catch (Throwable e) {
                LOGGER.error("Iris engine close failed for {}", entry.getKey().dimension().identifier(), e);
            }
        }
        ENGINES.clear();
    }
}
