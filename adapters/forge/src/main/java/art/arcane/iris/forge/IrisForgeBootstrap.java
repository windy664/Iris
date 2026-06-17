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

package art.arcane.iris.forge;

import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedForcedDatapack;
import art.arcane.iris.modded.ModdedIrisLog;
import art.arcane.iris.modded.ModdedParityProbe;
import art.arcane.iris.modded.ModdedWorldCheck;
import art.arcane.iris.modded.ModdedWorldEngines;
import art.arcane.iris.modded.command.IrisModdedCommands;
import art.arcane.iris.modded.command.ModdedObjectUndo;
import art.arcane.iris.modded.command.ModdedWandService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.VersionInfo;
import net.minecraftforge.registries.DeferredRegister;

import java.util.function.Predicate;

@Mod("irisworldgen")
public final class IrisForgeBootstrap {
    public IrisForgeBootstrap(FMLJavaModLoadingContext context) {
        ModdedEngineBootstrap.initialize(new ForgeModdedLoader());
        String modVersion = ModList.getModContainerById("irisworldgen")
            .map((ModContainer container) -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
        VersionInfo versionInfo = FMLLoader.versionInfo();
        ModdedIrisLog.info("Iris " + modVersion + " bootstrapping on Minecraft " + versionInfo.mcVersion() + " (Forge " + versionInfo.forgeVersion() + ")");

        ModdedEngineBootstrap.selfTest(IrisForgeBootstrap.class.getClassLoader());
        ModdedEngineBootstrap.bind();

        DeferredRegister<MapCodec<? extends ChunkGenerator>> chunkGenerators = DeferredRegister.create(Registries.CHUNK_GENERATOR, "irisworldgen");
        chunkGenerators.register("iris", () -> IrisModdedChunkGenerator.CODEC);
        chunkGenerators.register(context.getModBusGroup());
        ModdedIrisLog.info("Iris chunk generator registered as irisworldgen:iris");

        ServerStoppingEvent.BUS.addListener((ServerStoppingEvent event) -> {
            ModdedObjectUndo.clearAll();
            ModdedWandService.clearAll();
            ModdedWorldEngines.shutdown();
            ModdedEngineBootstrap.stop();
        });
        AddPackFindersEvent.BUS.addListener((AddPackFindersEvent event) -> {
            if (event.getPackType() == PackType.SERVER_DATA) {
                event.addRepositorySource(ModdedForcedDatapack.repositorySource());
            }
        });
        RegisterCommandsEvent.BUS.addListener((RegisterCommandsEvent event) -> IrisModdedCommands.register(event.getDispatcher()));
        PlayerInteractEvent.LeftClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.LeftClickBlock>) (PlayerInteractEvent.LeftClickBlock event) ->
                ModdedWandService.attackBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getPos()));
        PlayerInteractEvent.RightClickBlock.BUS.addListener((Predicate<PlayerInteractEvent.RightClickBlock>) (PlayerInteractEvent.RightClickBlock event) ->
                ModdedWandService.useBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getPos()));
        TickEvent.ServerTickEvent.Post.BUS.addListener((TickEvent.ServerTickEvent.Post event) -> {
            ModdedEngineBootstrap.tick(event.server());
            ModdedWandService.serverTick(event.server());
        });

        String parity = System.getProperty("iris.parity");
        if (parity != null) {
            ModdedIrisLog.info("Iris parity probe armed: " + parity);
            ModdedParityProbe.schedule(parity);
        }

        String worldCheck = System.getProperty("iris.worldcheck");
        if (worldCheck != null) {
            ModdedIrisLog.info("Iris world check armed");
            ModdedWorldCheck.schedule();
        }
    }
}
