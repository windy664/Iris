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

package art.arcane.iris.fabric;

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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Chunk;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.function.Supplier;

public final class FabricEngineBootstrap {
    private static final Object LOCK = new Object();
    private static volatile FabricPlatform platform;

    private FabricEngineBootstrap() {
    }

    public static MinecraftServer currentServer() {
        Object instance = FabricLoader.getInstance().getGameInstance();
        return instance instanceof MinecraftServer server ? server : null;
    }

    public static FabricPlatform bind() {
        return bind(FabricEngineBootstrap::currentServer);
    }

    public static FabricPlatform bind(Supplier<MinecraftServer> server) {
        FabricPlatform bound = platform;
        if (bound != null) {
            return bound;
        }
        synchronized (LOCK) {
            if (platform != null) {
                return platform;
            }
            FabricPlatform created = new FabricPlatform(server);
            IrisPlatforms.bind(created);
            IrisObjectRotation.bindFallbackRotator(new FabricStateRotator());
            BlockDataMergeSupport.bindFallbackMerger(new FabricStateMerger());
            TileData.bindFallbackReader(new FabricTileReader(server));
            FabricDecoratorHooks decoratorHooks = new FabricDecoratorHooks();
            DecoratorPlatformHooks.bind(decoratorHooks, decoratorHooks);
            IrisServices.register(PreservationRegistry.class, new InertPreservation());
            IrisServices.register(EngineWorldManagerProvider.class, (EngineWorldManagerProvider) (Engine engine) -> new InertWorldManager());
            platform = created;
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
