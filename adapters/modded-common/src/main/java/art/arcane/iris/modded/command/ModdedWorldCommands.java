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

package art.arcane.iris.modded.command;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedModConfig;
import art.arcane.iris.modded.ModdedPrimaryWorldRouter;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class ModdedWorldCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final String DEFAULT_NAMESPACE = "irisworldgen";
    private static final SuggestionProvider<CommandSourceStack> LOADED_DIMENSIONS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(loadedIrisDimensions(context.getSource().getServer()), builder);

    private ModdedWorldCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("status")
                .executes((CommandContext<CommandSourceStack> context) -> status(context.getSource())));
        root.then(Commands.literal("list")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));
        root.then(Commands.literal("ls")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));

        root.then(enableTree("enable"));
        root.then(enableTree("create"));
        root.then(replaceOverworldTree());

        root.then(disableTree("disable"));
        root.then(disableTree("remove"));
        root.then(disableTree("rm"));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enableTree(String name) {
        return Commands.literal(name)
                .then(Commands.argument("dimension", StringArgumentType.word())
                        .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> enable(context.getSource(),
                                        StringArgumentType.getString(context, "dimension"),
                                        StringArgumentType.getString(context, "pack"),
                                        StringArgumentType.getString(context, "pack"),
                                        1337L))
                                .then(Commands.argument("packDimension", StringArgumentType.word())
                                        .executes((CommandContext<CommandSourceStack> context) -> enable(context.getSource(),
                                                StringArgumentType.getString(context, "dimension"),
                                                StringArgumentType.getString(context, "pack"),
                                                StringArgumentType.getString(context, "packDimension"),
                                                1337L)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> disableTree(String name) {
        return Commands.literal(name)
                .then(Commands.argument("dimension", StringArgumentType.word()).suggests(LOADED_DIMENSIONS)
                        .executes((CommandContext<CommandSourceStack> context) -> disable(context.getSource(), StringArgumentType.getString(context, "dimension"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> replaceOverworldTree() {
        return Commands.literal("replace-overworld")
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> replaceOverworld(context.getSource(),
                                StringArgumentType.getString(context, "pack"),
                                StringArgumentType.getString(context, "pack")))
                        .then(Commands.argument("packDimension", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) -> replaceOverworld(context.getSource(),
                                        StringArgumentType.getString(context, "pack"),
                                        StringArgumentType.getString(context, "packDimension")))));
    }

    public static int createWorld(CommandSourceStack source, String name, String pack, long seed) {
        return enable(source, name, pack, pack, seed);
    }

    private static int enable(CommandSourceStack source, String targetDimension, String packName, String packDimension, long seed) {
        MinecraftServer server = source.getServer();
        String dimensionId;
        try {
            dimensionId = normalizeDimensionId(targetDimension);
        } catch (IllegalArgumentException e) {
            IrisModdedCommands.fail(source, e.getMessage());
            return 0;
        }
        if (!loadPackDimension(source, packName, packDimension)) {
            return 0;
        }
        try {
            ModdedDimensionManager.createPersistent(server, dimensionId, packDimension, seed);
        } catch (Throwable e) {
            LOGGER.error("Iris world injection failed for {} (pack={} dim={})", dimensionId, packName, packDimension, e);
            IrisModdedCommands.fail(source, "Failed to inject Iris world '" + dimensionId + "': " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        IrisModdedCommands.ok(source, "Created Iris world " + dimensionId + " from pack '" + packName + "' dimension '" + packDimension + "' (seed " + seed + ").");
        IrisModdedCommands.ok(source, "It is live now and re-injected on every startup. Teleport in with /iris world status or a portal; no restart required.");
        return 1;
    }

    private static int replaceOverworld(CommandSourceStack source, String packName, String packDimension) {
        MinecraftServer server = source.getServer();
        String dimensionId = DEFAULT_NAMESPACE + ":primary";
        if (!loadPackDimension(source, packName, packDimension)) {
            return 0;
        }
        try {
            ModdedDimensionManager.createPersistent(server, dimensionId, packDimension, 1337L);
        } catch (Throwable e) {
            LOGGER.error("Iris primary world injection failed for {} (pack={} dim={})", dimensionId, packName, packDimension, e);
            IrisModdedCommands.fail(source, "Failed to inject Iris primary world: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        ModdedModConfig.setPrimaryWorld(dimensionId);
        ModdedPrimaryWorldRouter.clear();
        IrisModdedCommands.ok(source, "Iris primary world set to " + dimensionId + " (pack '" + packName + "' dimension '" + packDimension + "').");
        IrisModdedCommands.ok(source, "The vanilla overworld generator cannot be hot-swapped, so this does NOT regenerate the existing overworld.");
        IrisModdedCommands.ok(source, "Instead, " + dimensionId + " is now the configured primary world: players in the vanilla overworld are routed there on join, and it re-injects on every startup.");
        return 1;
    }

    private static boolean loadPackDimension(CommandSourceStack source, String packName, String packDimension) {
        File packFolder = new File(ModdedPackCommands.packsRoot(), packName);
        if (!packFolder.isDirectory()) {
            IrisModdedCommands.fail(source, "Pack '" + packName + "' was not found under " + ModdedPackCommands.packsRoot().getAbsolutePath());
            return false;
        }
        IrisData data = IrisData.get(packFolder);
        IrisDimension dimension = data.getDimensionLoader().load(packDimension);
        if (dimension == null) {
            IrisModdedCommands.fail(source, "Pack '" + packName + "' does not contain dimensions/" + packDimension + ".json");
            return false;
        }
        return true;
    }

    private static int disable(CommandSourceStack source, String targetDimension) {
        MinecraftServer server = source.getServer();
        String dimensionId;
        try {
            dimensionId = normalizeDimensionId(targetDimension);
        } catch (IllegalArgumentException e) {
            IrisModdedCommands.fail(source, e.getMessage());
            return 0;
        }
        boolean removed;
        try {
            removed = ModdedDimensionManager.removePersistent(server, dimensionId);
        } catch (Throwable e) {
            LOGGER.error("Iris world removal failed for {}", dimensionId, e);
            IrisModdedCommands.fail(source, "Failed to remove Iris world '" + dimensionId + "': " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        if (dimensionId.equals(ModdedModConfig.get().primaryWorld())) {
            ModdedModConfig.setPrimaryWorld("");
            ModdedPrimaryWorldRouter.clear();
        }
        if (!removed) {
            IrisModdedCommands.ok(source, "Iris world '" + dimensionId + "' was not loaded; cleared its persistent registry entry.");
            return 1;
        }
        IrisModdedCommands.ok(source, "Removed Iris world '" + dimensionId + "': evacuated, unloaded, region data deleted, and dropped from the startup registry.");
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
                loaded++;
                IrisModdedCommands.ok(source, "Loaded Iris level: " + level.dimension().identifier() + " -> pack dimension '" + generator.activePackKey() + "'");
            }
        }
        String primary = ModdedModConfig.get().primaryWorld();
        if (!primary.isBlank()) {
            IrisModdedCommands.ok(source, "Primary world: " + primary + (ModdedModConfig.get().routePlayersToPrimaryWorld() ? " (players routed there)" : " (routing disabled)"));
        }
        if (loaded == 0) {
            IrisModdedCommands.fail(source, "No Iris dimensions are currently loaded. Create one with /iris world create <name> <pack>.");
        }
        return loaded > 0 ? 1 : 0;
    }

    private static int list(CommandSourceStack source) {
        List<String> dimensions = loadedIrisDimensions(source.getServer());
        IrisModdedCommands.ok(source, "Loaded Iris dimensions: " + dimensions.size());
        for (String dimension : dimensions) {
            IrisModdedCommands.ok(source, "  - " + dimension);
        }
        if (dimensions.isEmpty()) {
            IrisModdedCommands.ok(source, "Use /iris world create <name> <pack> to inject one without restarting.");
        }
        return 1;
    }

    private static List<String> loadedIrisDimensions(MinecraftServer server) {
        List<String> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                dimensions.add(level.dimension().identifier().toString());
            }
        }
        return dimensions;
    }

    private static String normalizeDimensionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing dimension id.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String namespace = DEFAULT_NAMESPACE;
        String path = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            path = normalized.substring(colon + 1);
        }
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+") || path.startsWith("/") || path.endsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("Invalid dimension id '" + value + "'. Use name or namespace:path.");
        }
        return namespace + ":" + path;
    }
}
