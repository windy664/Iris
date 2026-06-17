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

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModdedStartup {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private ModdedStartup() {
    }

    public static void runOnce(MinecraftServer server) {
        if (server == null || !STARTED.compareAndSet(false, true)) {
            return;
        }
        reinjectPersistentDimensions(server);

        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            ensureDefaultPack();
            return;
        }
        scheduler.async(ModdedStartup::ensureDefaultPack);
    }

    private static void reinjectPersistentDimensions(MinecraftServer server) {
        List<ModdedDimensionRegistryStore.PersistentDimension> dimensions = ModdedDimensionRegistryStore.load(server);
        if (dimensions.isEmpty()) {
            return;
        }
        int injected = 0;
        for (ModdedDimensionRegistryStore.PersistentDimension dimension : dimensions) {
            try {
                ModdedDimensionManager.create(server, dimension.id(), dimension.packKey(), dimension.seed());
                injected++;
            } catch (Throwable e) {
                LOGGER.error("Iris failed to re-inject persistent dimension '{}' (pack={} seed={})", dimension.id(), dimension.packKey(), dimension.seed(), e);
            }
        }
        LOGGER.info("Iris re-injected {} persistent dimension(s) at startup", injected);
    }

    private static void ensureDefaultPack() {
        ModdedModConfig config = ModdedModConfig.get();
        if (!config.autoDownloadDefaultPack()) {
            return;
        }
        String pack = config.defaultPack();
        Path configDir = ModdedEngineBootstrap.loader().configDir();
        File packFolder = configDir.resolve("irisworldgen").resolve("packs").resolve(pack).toFile();
        if (new File(packFolder, "dimensions/" + pack + ".json").isFile()) {
            return;
        }
        LOGGER.info("Iris default pack '{}' missing; downloading IrisDimensions/{} (master)", pack, pack);
        boolean installed = ModdedPackInstaller.install(configDir, pack, "master", (String line) -> LOGGER.info("Iris: {}", line));
        if (!installed) {
            LOGGER.warn("Iris default pack '{}' could not be downloaded; install it with /iris download {}", pack, pack);
        }
    }
}
