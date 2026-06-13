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

import art.arcane.iris.engine.decorator.DecoratorPlatformHooks;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineWorldManager;
import art.arcane.iris.engine.framework.EngineWorldManagerProvider;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.object.BlockDataMergeSupport;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisServices;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Chunk;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModdedEngineBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final String[] CORE_SELF_TEST_CLASSES = {
        "art.arcane.iris.engine.IrisEngine",
        "art.arcane.iris.util.common.data.B",
        "art.arcane.iris.core.loader.IrisData"
    };
    private static final Object LOCK = new Object();
    private static volatile ModdedLoader loader;
    private static volatile ModdedPlatform platform;

    private ModdedEngineBootstrap() {
    }

    public static void initialize(ModdedLoader moddedLoader) {
        loader = moddedLoader;
    }

    public static ModdedLoader loader() {
        ModdedLoader bound = loader;
        if (bound == null) {
            throw new IllegalStateException("Iris modded loader is not initialized; the loader bootstrap must call ModdedEngineBootstrap.initialize first");
        }
        return bound;
    }

    public static MinecraftServer currentServer() {
        return loader().currentServer();
    }

    public static void selfTest(ClassLoader classLoader) {
        int loadedClasses = 0;
        for (String className : CORE_SELF_TEST_CLASSES) {
            try {
                Class.forName(className, true, classLoader);
                loadedClasses++;
            } catch (Throwable error) {
                LOGGER.error("Iris core self-test failed to initialize {}", className, error);
            }
        }

        if (loadedClasses != CORE_SELF_TEST_CLASSES.length) {
            throw new IllegalStateException("Iris core self-test failed: only " + loadedClasses + " of " + CORE_SELF_TEST_CLASSES.length + " engine classes initialized");
        }

        ModdedIrisLog.info("Iris core loaded (" + loadedClasses + " classes ok)");
    }

    public static ModdedPlatform bind() {
        ModdedPlatform bound = platform;
        if (bound != null) {
            return bound;
        }
        synchronized (LOCK) {
            if (platform != null) {
                return platform;
            }
            ModdedLoader boundLoader = loader();
            ModdedPlatform created = new ModdedPlatform(boundLoader);
            IrisPlatforms.bind(created);
            IrisObjectRotation.bindFallbackRotator(new ModdedStateRotator());
            BlockDataMergeSupport.bindFallbackMerger(new ModdedStateMerger());
            TileData.bindFallbackReader(new ModdedTileReader(boundLoader::currentServer));
            ModdedDecoratorHooks decoratorHooks = new ModdedDecoratorHooks();
            DecoratorPlatformHooks.bind(decoratorHooks, decoratorHooks);
            IrisServices.register(PreservationRegistry.class, new InertPreservation());
            IrisServices.register(EngineWorldManagerProvider.class, (EngineWorldManagerProvider) (Engine engine) -> new InertWorldManager());
            platform = created;
            ModdedIrisSplash.print(boundLoader);
            return created;
        }
    }

    private static final class InertPreservation implements PreservationRegistry {
        @Override
        public void register(Thread thread) {
        }

        @Override
        public void registerCache(MeteredCache cache) {
        }

        @Override
        public void dereference() {
        }
    }

    private static final class InertWorldManager implements EngineWorldManager {
        @Override
        public void close() {
        }

        @Override
        public int getEntityCount() {
            return 0;
        }

        @Override
        public int getChunkCount() {
            return 0;
        }

        @Override
        public double getEntitySaturation() {
            return 0;
        }

        @Override
        public void onTick() {
        }

        @Override
        public void onSave() {
        }

        @Override
        public void onBlockBreak(BlockBreakEvent e) {
        }

        @Override
        public void onBlockPlace(BlockPlaceEvent e) {
        }

        @Override
        public void onChunkLoad(Chunk e, boolean generated) {
        }

        @Override
        public void onChunkUnload(Chunk e) {
        }

        @Override
        public void teleportAsync(PlayerTeleportEvent e) {
        }
    }
}
