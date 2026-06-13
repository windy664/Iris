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

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.Locator;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedLoader;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.math.Position2;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

public final class IrisModdedCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final long LOCATE_TIMEOUT_MS = 120000L;

    private static final SuggestionProvider<CommandSourceStack> BIOME_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestBiomeKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> REGION_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestRegionKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> OBJECT_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestObjectKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestStructureKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> POI_TYPES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(List.of("buried_treasure"), builder);
    static final SuggestionProvider<CommandSourceStack> PACK_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestPackNames(context, builder);
    private static final SuggestionProvider<CommandSourceStack> DIMENSION_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestDimensionNames(context, builder);

    private IrisModdedCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(rootTree());
        dispatcher.register(Commands.literal("ir").redirect(root));
        dispatcher.register(Commands.literal("irs").redirect(root));
        IrisLogging.info("Iris /iris command tree registered");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rootTree() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("iris");

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), ""));
        root.then(helpTree());

        root.then(Commands.literal("version")
                .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource())));

        root.then(Commands.literal("info").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), null))
                .then(Commands.argument("dimension", StringArgumentType.greedyString()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), StringArgumentType.getString(context, "dimension")))));

        root.then(Commands.literal("what").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> what(context.getSource())));

        root.then(gotoTree("goto"));
        root.then(gotoTree("find"));

        root.then(Commands.literal("seed").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> seed(context.getSource())));

        root.then(goldenhashTree("goldenhash"));
        root.then(goldenhashTree("gold"));

        root.then(downloadTree("download"));
        root.then(downloadTree("dl"));

        root.then(metricsTree("metrics"));
        root.then(metricsTree("measure"));

        root.then(regenTree("regen"));
        root.then(regenTree("rg"));

        root.then(pregenTree("pregen"));
        root.then(pregenTree("pregenerate"));

        root.then(Commands.literal("wand").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedObjectCommands.giveWand(context.getSource())));
        root.then(ModdedObjectCommands.tree("object"));
        root.then(ModdedObjectCommands.tree("o"));
        root.then(editTree());

        root.then(ModdedStudioCommands.tree("studio"));
        root.then(ModdedStudioCommands.tree("std"));
        root.then(ModdedStudioCommands.tree("s"));
        root.then(ModdedPackCommands.tree("pack"));
        root.then(ModdedPackCommands.tree("pk"));
        root.then(ModdedWorldCommands.tree("world"));
        root.then(ModdedWorldCommands.tree("w"));
        root.then(ModdedDatapackCommands.tree("datapack"));
        root.then(ModdedDatapackCommands.tree("datapacks"));
        root.then(ModdedDatapackCommands.tree("dp"));
        root.then(ModdedStructureCommands.tree("structure"));
        root.then(ModdedStructureCommands.tree("struct"));
        root.then(ModdedStructureCommands.tree("str"));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> helpTree() {
        return Commands.literal("help")
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), ""))
                .then(Commands.argument("section", StringArgumentType.greedyString())
                        .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), StringArgumentType.getString(context, "section"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> downloadTree(String name) {
        return Commands.literal(name).requires(GATE)
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> download(context.getSource(), StringArgumentType.getString(context, "pack"), "stable"))
                        .then(Commands.argument("branch", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) -> download(context.getSource(), StringArgumentType.getString(context, "pack"), StringArgumentType.getString(context, "branch")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> metricsTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> metrics(context.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> regenTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> regen(context.getSource(), 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                        .executes((CommandContext<CommandSourceStack> context) -> regen(context.getSource(), IntegerArgumentType.getInteger(context, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> gotoTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name))
                .then(Commands.literal("biome")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(BIOME_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoBiome(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("region")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(REGION_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoRegion(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("object")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoObject(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("structure")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(STRUCTURE_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoStructure(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("poi")
                        .then(Commands.argument("type", StringArgumentType.greedyString()).suggests(POI_TYPES)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoPoi(context.getSource(), StringArgumentType.getString(context, "type")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pregenTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name))
                .then(Commands.literal("start")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), 0, 0))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z")))))))
                .then(Commands.literal("stop")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStop(context.getSource())))
                .then(Commands.literal("x")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStop(context.getSource())))
                .then(Commands.literal("pause")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenPause(context.getSource())))
                .then(Commands.literal("resume")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenPause(context.getSource())))
                .then(Commands.literal("status")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStatus(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> goldenhashTree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> radiusAndThreads = Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), 8, 8, ModdedGoldenHash.Mode.AUTO));
        attachModes(radiusAndThreads, (CommandContext<CommandSourceStack> context) -> 8, (CommandContext<CommandSourceStack> context) -> 8);

        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> radius = Commands.argument("radius", IntegerArgumentType.integer(0, 256))
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), 8, ModdedGoldenHash.Mode.AUTO));
        attachModes(radius, (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "radius"), (CommandContext<CommandSourceStack> context) -> 8);

        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> threads = Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "threads"), ModdedGoldenHash.Mode.AUTO));
        attachModes(threads, (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "radius"), (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "threads"));

        radius.then(threads);
        radiusAndThreads.then(radius);
        return radiusAndThreads;
    }

    private interface IntExtractor {
        int extract(CommandContext<CommandSourceStack> context);
    }

    private static void attachModes(com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> node, IntExtractor radius, IntExtractor threads) {
        node.then(Commands.literal("capture")
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), radius.extract(context), threads.extract(context), ModdedGoldenHash.Mode.CAPTURE)));
        node.then(Commands.literal("verify")
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), radius.extract(context), threads.extract(context), ModdedGoldenHash.Mode.VERIFY)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> editTree() {
        String message = "/iris edit opens pack JSON files in a desktop editor through the Bukkit studio toolchain; "
                + "on modded servers edit the pack files directly under config/irisworldgen/packs/<pack>/.";
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("edit").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> {
                    fail(context.getSource(), message);
                    return 0;
                });
        for (String child : new String[]{"biome", "region", "dimension"}) {
            node.then(Commands.literal(child)
                    .executes((CommandContext<CommandSourceStack> context) -> {
                        fail(context.getSource(), message);
                        return 0;
                    }));
        }
        return node;
    }

    private static int regen(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator irisGenerator)) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ModdedRegen.start(source, level, irisGenerator, engine, player, radius);
        return 1;
    }

    private static int pregenStart(CommandSourceStack source, int radius, int centerX, int centerZ) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        if (!ModdedPregenJob.start(level, engine, radius, centerX, centerZ)) {
            fail(source, "A pregeneration task is already running. Stop it first with /iris pregen stop.");
            return 0;
        }
        ok(source, "Pregen started in " + level.dimension().identifier() + " of " + (radius * 2) + " by " + (radius * 2)
                + " blocks from " + centerX + "," + centerZ + ". Progress logs to console; see /iris pregen status.");
        return 1;
    }

    private static int pregenStop(CommandSourceStack source) {
        if (ModdedPregenJob.stop()) {
            ok(source, "Stopping pregeneration; finishing up the current region...");
            return 1;
        }
        fail(source, "No active pregeneration task to stop.");
        return 0;
    }

    private static int pregenPause(CommandSourceStack source) {
        Boolean paused = ModdedPregenJob.pauseResume();
        if (paused == null) {
            fail(source, "No active pregeneration task to pause/resume.");
            return 0;
        }
        ok(source, "Pregeneration is now " + (paused.booleanValue() ? "paused" : "running") + ".");
        return 1;
    }

    private static int pregenStatus(CommandSourceStack source) {
        Component status = ModdedPregenJob.statusComponent();
        if (status == null) {
            fail(source, "No active pregeneration task.");
            return 0;
        }
        ok(source, status);
        return 1;
    }

    private static int version(CommandSourceStack source) {
        ModdedLoader loader = ModdedEngineBootstrap.loader();
        int engines = engineCount(source.getServer());
        ok(source, "Iris " + loader.modVersion() + " by Volmit Software on " + loader.platformName()
                + " (Minecraft " + loader.minecraftVersion() + "), " + engines + " Iris dimension(s)");
        return 1;
    }

    private static int info(CommandSourceStack source, String filter) {
        MinecraftServer server = source.getServer();
        List<String> lines = new ArrayList<>();
        int total = 0;
        int iris = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total++;
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (!(generator instanceof IrisModdedChunkGenerator irisGenerator)) {
                continue;
            }
            iris++;
            String dimensionId = level.dimension().identifier().toString();
            if (filter != null && !dimensionId.contains(filter) && !irisGenerator.dimensionKey().contains(filter)) {
                continue;
            }
            Engine engine = irisGenerator.engineIfBound();
            if (engine == null) {
                lines.add(dimensionId + ": pack=" + irisGenerator.dimensionKey() + " (engine not started yet)");
                continue;
            }
            lines.add(dimensionId + ": pack=" + engine.getDimension().getLoadKey()
                    + " seed=" + level.getSeed()
                    + " height=" + engine.getMinHeight() + ".." + engine.getMaxHeight()
                    + " generated=" + engine.getGenerated()
                    + " data=" + engine.getData().getDataFolder().getAbsolutePath());
        }
        ok(source, "Loaded dimensions: " + total + " (" + iris + " Iris)");
        if (lines.isEmpty()) {
            ok(source, filter == null ? "No Iris dimensions are loaded." : "No Iris dimension matches '" + filter + "'.");
            return 0;
        }
        for (String line : lines) {
            ok(source, line);
        }
        return 1;
    }

    private static int what(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        BlockPos pos = player.blockPosition();
        int relativeY = pos.getY() - engine.getMinHeight();
        try {
            IrisBiome biome = engine.getBiome(pos.getX(), relativeY, pos.getZ());
            ok(source, "Biome: " + biome.getLoadKey() + " (" + biome.getName() + ")");
        } catch (Throwable e) {
            fail(source, "Biome lookup failed: " + e.getClass().getSimpleName());
        }
        try {
            IrisRegion region = engine.getRegion(pos.getX(), pos.getZ());
            ok(source, "Region: " + region.getLoadKey() + " (" + region.getName() + ")");
        } catch (Throwable e) {
            fail(source, "Region lookup failed: " + e.getClass().getSimpleName());
        }
        try {
            IrisBiome cave = engine.getCaveBiome(pos.getX(), relativeY, pos.getZ());
            ok(source, "Cave biome: " + (cave == null ? "none" : cave.getLoadKey()));
        } catch (Throwable e) {
            fail(source, "Cave biome lookup failed: " + e.getClass().getSimpleName());
        }
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        BlockState surface = level.getBlockState(new BlockPos(pos.getX(), surfaceY - 1, pos.getZ()));
        ok(source, "Surface block: " + BuiltInRegistries.BLOCK.getKey(surface.getBlock()) + " (y=" + (surfaceY - 1) + ")");
        ok(source, "Position: " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (chunk " + (pos.getX() >> 4) + "," + (pos.getZ() >> 4) + ")");
        return 1;
    }

    private static int gotoBiome(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisBiome biome = engine.getData().getBiomeLoader().load(key.trim());
        if (biome == null) {
            fail(source, "Unknown biome: " + key);
            return 0;
        }
        locate(source, level, engine, player, Locator.surfaceBiome(biome.getLoadKey()), "biome " + biome.getLoadKey());
        return 1;
    }

    private static int gotoRegion(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisRegion region = engine.getData().getRegionLoader().load(key.trim());
        if (region == null) {
            fail(source, "Unknown region: " + key);
            return 0;
        }
        if (!engine.getDimension().getRegions().contains(region.getLoadKey())) {
            fail(source, region.getLoadKey() + " is not defined in the dimension!");
            return 0;
        }
        locate(source, level, engine, player, Locator.region(region.getLoadKey()), "region " + region.getLoadKey());
        return 1;
    }

    private static int gotoObject(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String key = keyRaw.trim();
        if (!engine.hasObjectPlacement(key)) {
            fail(source, key + " is not configured in any region/biome object placements ("
                    + engine.getData().getObjectLoader().getPossibleKeys().length + " object keys loaded).");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players. (object key '" + key + "' resolved against "
                    + engine.getData().getObjectLoader().getPossibleKeys().length + " loaded object keys)");
            return 0;
        }
        locate(source, level, engine, player, Locator.object(key), "object " + key);
        return 1;
    }

    private static int gotoStructure(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String key = keyRaw.trim();
        Set<String> placed = IrisStructureLocator.placedKeys(engine);
        if (!IrisStructureLocator.isPlaced(engine, key)) {
            fail(source, "Structure " + key + " is not placed by this pack. Placed keys (" + placed.size() + "): "
                    + String.join(", ", placed.stream().limit(8).toList()) + (placed.size() > 8 ? ", ..." : ""));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players. (structure key '" + key + "' resolved; "
                    + placed.size() + " placed structure keys loaded)");
            return 0;
        }
        MinecraftServer server = source.getServer();
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        ok(source, "Searching for structure " + key + "...");
        Thread thread = new Thread(() -> {
            try {
                int[] at = IrisStructureLocator.locate(engine, key, blockX, blockZ, 1024);
                if (at == null) {
                    server.execute(() -> fail(source, "Could not find structure " + key + " within 1024 chunks."));
                    return;
                }
                int targetX = at[0] + 8;
                int targetY = at[1] + 2;
                int targetZ = at[2] + 8;
                server.execute(() -> {
                    player.teleportTo(level, targetX + 0.5D, targetY, targetZ + 0.5D, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
                    ok(source, "Teleported to structure " + key + " at " + targetX + " " + targetY + " " + targetZ);
                });
            } catch (Throwable e) {
                LOGGER.error("Iris structure locate failed for {}", key, e);
                server.execute(() -> fail(source, "Search failed: " + e.getClass().getSimpleName()));
            }
        }, "Iris Structure Locator");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int gotoPoi(CommandSourceStack source, String typeRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String type = typeRaw.trim();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players. (POI type '" + type + "' accepted)");
            return 0;
        }
        locate(source, level, engine, player, Locator.poi(type), "POI " + type);
        return 1;
    }

    private static void locate(CommandSourceStack source, ServerLevel level, Engine engine, ServerPlayer player, Locator<?> locator, String label) {
        MinecraftServer server = source.getServer();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        ok(source, "Searching for " + label + "...");
        Thread thread = new Thread(() -> {
            try {
                Position2 at = locator.find(engine, new Position2(chunkX, chunkZ), LOCATE_TIMEOUT_MS, (Integer checks) -> {
                }).get();
                if (at == null) {
                    server.execute(() -> fail(source, "Could not find " + label + " within the search timeout."));
                    return;
                }
                int blockX = (at.getX() << 4) + 8;
                int blockZ = (at.getZ() << 4) + 8;
                int blockY = engine.getMinHeight() + engine.getHeight(blockX, blockZ, false) + 2;
                server.execute(() -> {
                    player.teleportTo(level, blockX + 0.5D, blockY, blockZ + 0.5D, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
                    ok(source, "Teleported to " + label + " at " + blockX + " " + blockY + " " + blockZ);
                });
            } catch (WrongEngineBroException e) {
                server.execute(() -> fail(source, "The engine for this world has been closed; rejoin the dimension and try again."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOGGER.error("Iris locate failed for {}", label, e);
                server.execute(() -> fail(source, "Search failed: " + e.getCause()));
            }
        }, "Iris Locator");
        thread.setDaemon(true);
        thread.start();
    }

    private static int seed(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ok(source, "World seed: " + level.getSeed());
        ok(source, "Engine seed: " + engine.getSeedManager().getSeed() + " (mixed: " + engine.getSeedManager().getFullMixedSeed() + ")");
        return 1;
    }

    private static int goldenhash(CommandSourceStack source, int radius, int threads, ModdedGoldenHash.Mode mode) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ModdedGoldenHash.start(source, level, engine, radius, threads, mode);
        return 1;
    }

    private static int download(CommandSourceStack source, String pack, String branch) {
        MinecraftServer server = source.getServer();
        ok(source, "Downloading IrisDimensions/" + pack + " (branch " + branch + ")...");
        Thread thread = new Thread(() -> {
            boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), pack, branch,
                    (String message) -> server.execute(() -> ok(source, message)));
            if (installed) {
                server.execute(() -> ok(source, "Pack '" + pack + "' installed. Restart or create a new world to use it."));
            } else {
                server.execute(() -> fail(source, "Pack download failed for " + pack + "/" + branch + " (see console)."));
            }
        }, "Iris Pack Download");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int metrics(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ok(source, "Generated: " + engine.getGenerated() + " chunk(s), " + String.format("%.1f", engine.getGeneratedPerSecond()) + "/s");
        KMap<String, Double> pulled = engine.getMetrics().pull();
        Map<String, Double> sorted = new TreeMap<>(pulled);
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0D) {
                continue;
            }
            ok(source, "  " + entry.getKey() + ": " + String.format("%.2f", entry.getValue()) + "ms");
        }
        return 1;
    }

    static Engine engineFor(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof IrisModdedChunkGenerator irisGenerator) {
            try {
                return irisGenerator.commandEngine();
            } catch (Throwable e) {
                LOGGER.error("Iris engine lookup failed for {}", level.dimension().identifier(), e);
                return null;
            }
        }
        return null;
    }

    private static int engineCount(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                count++;
            }
        }
        return count;
    }

    private static CompletableFuture<Suggestions> suggestBiomeKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getBiomeLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRegionKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getDimension().getRegions(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestObjectKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getObjectLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestStructureKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(IrisStructureLocator.placedKeys(engine), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPackNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        List<String> names = new ArrayList<>();
        names.add("overworld");
        try {
            File packs = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("packs").toFile();
            File[] children = packs.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !names.contains(child.getName())) {
                        names.add(child.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestDimensionNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        List<String> names = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                names.add(level.dimension().identifier().toString());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    static void ok(CommandSourceStack source, String message) {
        ModdedCommandFeedback.ok(source, message);
    }

    static void ok(CommandSourceStack source, Component component) {
        ModdedCommandFeedback.ok(source, component);
    }

    static void fail(CommandSourceStack source, String message) {
        ModdedCommandFeedback.fail(source, message);
    }
}
