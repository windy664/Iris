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

import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedParityProbe;
import art.arcane.iris.modded.ModdedWorldCheck;
import art.arcane.iris.modded.ModdedWorldEngines;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IrisFabricBootstrap implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    @Override
    public void onInitialize() {
        ModdedEngineBootstrap.initialize(new FabricModdedLoader());
        FabricLoader loader = FabricLoader.getInstance();
        String modVersion = loader.getModContainer("irisworldgen")
            .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        String minecraftVersion = loader.getModContainer("minecraft")
            .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        LOGGER.info("Iris {} bootstrapping on Minecraft {} (Fabric)", modVersion, minecraftVersion);

        ModdedEngineBootstrap.selfTest(IrisFabricBootstrap.class.getClassLoader());
        ModdedEngineBootstrap.bind();
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("irisworldgen", "iris"), IrisModdedChunkGenerator.CODEC);
        LOGGER.info("Iris chunk generator registered as irisworldgen:iris");
        ServerLifecycleEvents.SERVER_STOPPING.register((MinecraftServer server) -> ModdedWorldEngines.shutdown());

        String parity = System.getProperty("iris.parity");
        if (parity != null) {
            LOGGER.info("Iris parity probe armed: {}", parity);
            ModdedParityProbe.schedule(parity);
        }

        String worldCheck = System.getProperty("iris.worldcheck");
        if (worldCheck != null) {
            LOGGER.info("Iris world check armed");
            ModdedWorldCheck.schedule();
        }
    }
}
