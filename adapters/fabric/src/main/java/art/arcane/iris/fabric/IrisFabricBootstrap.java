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
import art.arcane.iris.modded.ModdedIrisLog;
import art.arcane.iris.modded.ModdedParityProbe;
import art.arcane.iris.modded.ModdedWorldCheck;
import art.arcane.iris.modded.ModdedWorldEngines;
import art.arcane.iris.modded.command.IrisModdedCommands;
import art.arcane.iris.modded.command.ModdedObjectUndo;
import art.arcane.iris.modded.command.ModdedWandService;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

public final class IrisFabricBootstrap implements ModInitializer {
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
        ModdedIrisLog.info("Iris " + modVersion + " bootstrapping on Minecraft " + minecraftVersion + " (Fabric)");

        ModdedEngineBootstrap.selfTest(IrisFabricBootstrap.class.getClassLoader());
        ModdedEngineBootstrap.bind();
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("irisworldgen", "iris"), IrisModdedChunkGenerator.CODEC);
        ModdedIrisLog.info("Iris chunk generator registered as irisworldgen:iris");
        ServerLifecycleEvents.SERVER_STOPPING.register((MinecraftServer server) -> {
            ModdedObjectUndo.clearAll();
            ModdedWandService.clearAll();
            ModdedWorldEngines.shutdown();
            ModdedEngineBootstrap.stop();
        });
        CommandRegistrationCallback.EVENT.register((CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection selection) -> IrisModdedCommands.register(dispatcher));
        AttackBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) ->
                ModdedWandService.attackBlock(player, level, hand, pos) ? InteractionResult.SUCCESS : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand, BlockHitResult hit) ->
                ModdedWandService.useBlock(player, level, hand, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            ModdedEngineBootstrap.tick(server);
            ModdedWandService.serverTick(server);
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
