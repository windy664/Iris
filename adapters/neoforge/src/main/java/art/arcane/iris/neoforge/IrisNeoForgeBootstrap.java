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

package art.arcane.iris.neoforge;

import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedParityProbe;
import art.arcane.iris.modded.ModdedWorldCheck;
import art.arcane.iris.modded.ModdedWorldEngines;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("irisworldgen")
public final class IrisNeoForgeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    public IrisNeoForgeBootstrap(IEventBus modBus) {
        ModdedEngineBootstrap.initialize(new NeoForgeModdedLoader());
        String modVersion = ModList.get().getModContainerById("irisworldgen")
            .map((ModContainer container) -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
        VersionInfo versionInfo = FMLLoader.getCurrent().getVersionInfo();
        LOGGER.info("Iris {} bootstrapping on Minecraft {} (NeoForge {})", modVersion, versionInfo.mcVersion(), versionInfo.neoForgeVersion());

        ModdedEngineBootstrap.selfTest(IrisNeoForgeBootstrap.class.getClassLoader());
        ModdedEngineBootstrap.bind();

        DeferredRegister<MapCodec<? extends ChunkGenerator>> chunkGenerators = DeferredRegister.create(Registries.CHUNK_GENERATOR, "irisworldgen");
        chunkGenerators.register("iris", () -> IrisModdedChunkGenerator.CODEC);
        chunkGenerators.register(modBus);
        LOGGER.info("Iris chunk generator registered as irisworldgen:iris");

        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> ModdedWorldEngines.shutdown());

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
