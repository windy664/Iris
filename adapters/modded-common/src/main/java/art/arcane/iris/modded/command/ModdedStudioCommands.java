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
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeGeneratorLink;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisEntitySpawn;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisSpawner;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.Spiraler;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ModdedStudioCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final String DEFAULT_TEMPLATE = "example";

    private ModdedStudioCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), DEFAULT_TEMPLATE))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "template"))))));
        root.then(Commands.literal("+")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), DEFAULT_TEMPLATE))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "template"))))));

        root.then(Commands.literal("package")
                .executes((CommandContext<CommandSourceStack> context) -> pkg(context.getSource(), null))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> pkg(context.getSource(), StringArgumentType.getString(context, "pack")))));

        root.then(Commands.literal("version")
                .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource(), null))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource(), StringArgumentType.getString(context, "pack")))));

        root.then(Commands.literal("regions")
                .executes((CommandContext<CommandSourceStack> context) -> regions(context.getSource(), 500))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 1000))
                        .executes((CommandContext<CommandSourceStack> context) -> regions(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))));

        root.then(openTree("open"));
        root.then(openTree("o"));
        root.then(message("close", "There are no studio worlds on modded servers (/iris studio open prepares the pack workflow instead), so there is nothing to close."));
        root.then(message("x", "There are no studio worlds on modded servers (/iris studio open prepares the pack workflow instead), so there is nothing to close."));
        root.then(message("tpstudio", "There are no temporary Bukkit studio worlds on modded servers to teleport to; use /iris studio open <pack> to prepare the pack workflow instead."));
        root.then(message("stp", "There are no temporary Bukkit studio worlds on modded servers to teleport to; use /iris studio open <pack> to prepare the pack workflow instead."));
        root.then(message("vscode", "VSCode launch and workspace generation are desktop features of the Bukkit studio toolchain; edit config/irisworldgen/packs/<pack> directly in your editor."));
        root.then(message("vsc", "VSCode launch and workspace generation are desktop features of the Bukkit studio toolchain; edit config/irisworldgen/packs/<pack> directly in your editor."));
        root.then(message("update", "Workspace regeneration (.code-workspace + JSON schemas) reads Bukkit registries (SchemaBuilder); run /iris studio update on a Bukkit server against this pack."));
        root.then(message("importvanilla", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("importv", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("iv", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("noise", "The noise explorer is a desktop GUI launched from the Bukkit plugin."));
        root.then(message("nmap", "The noise explorer is a desktop GUI launched from the Bukkit plugin."));
        root.then(message("map", "The world map renderer is a desktop GUI launched from the Bukkit plugin."));
        root.then(message("render", "The world map renderer is a desktop GUI launched from the Bukkit plugin."));
        root.then(message("loot", "Loot simulation opens a Bukkit chest inventory GUI; it is not available on modded servers."));
        root.then(message("profile", "Pack performance profiling is part of the Bukkit studio toolchain and is not ported to modded servers."));
        root.then(message("spawn", "Iris entity spawning uses the Bukkit entity pipeline and is not ported to modded servers."));
        root.then(message("summon", "Iris entity spawning uses the Bukkit entity pipeline and is not ported to modded servers."));
        root.then(message("objects", "The chunk object report reads Bukkit chunk data and is not ported to modded servers."));
        root.then(message("find-objects", "The chunk object report reads Bukkit chunk data and is not ported to modded servers."));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> openTree(String name) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> openHelp(context.getSource()))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> open(context.getSource(), StringArgumentType.getString(context, "pack"), 1337L))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes((CommandContext<CommandSourceStack> context) -> open(context.getSource(), StringArgumentType.getString(context, "pack"), LongArgumentType.getLong(context, "seed")))));
    }

    private static int openHelp(CommandSourceStack source) {
        IrisModdedCommands.fail(source, "Provide a dimension pack: /iris studio open <pack> [seed]");
        return 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> message(String name, String text) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> {
                    IrisModdedCommands.fail(context.getSource(), text);
                    return 0;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes((CommandContext<CommandSourceStack> context) -> {
                            IrisModdedCommands.fail(context.getSource(), text);
                            return 0;
                        }));
    }

    private static File resolvePack(CommandSourceStack source, String pack) {
        String name = pack;
        if (name == null || name.isBlank()) {
            Engine engine = IrisModdedCommands.engineFor(source.getLevel());
            if (engine == null || engine.getDimension() == null) {
                IrisModdedCommands.fail(source, "This dimension is not generated by Iris; specify a pack name explicitly.");
                return null;
            }
            name = engine.getDimension().getLoadKey();
        }
        File folder = new File(ModdedPackCommands.packsRoot(), name);
        if (!folder.isDirectory() || !new File(folder, "dimensions").isDirectory()) {
            IrisModdedCommands.fail(source, "Pack '" + name + "' not found under " + ModdedPackCommands.packsRoot().getAbsolutePath());
            return null;
        }
        return folder;
    }

    private static int open(CommandSourceStack source, String pack, long seed) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }

        IrisData data = IrisData.get(folder);
        IrisDimension dimension = data.getDimensionLoader().load(folder.getName());
        if (dimension == null) {
            IrisModdedCommands.fail(source, "Pack '" + folder.getName() + "' has no dimensions/" + folder.getName() + ".json");
            return 0;
        }

        IrisModdedCommands.ok(source, studioOpenComponent(dimension, folder, seed));
        return 1;
    }

    private static MutableComponent studioOpenComponent(IrisDimension dimension, File folder, long seed) {
        String dimensionKey = dimension.getLoadKey() == null ? folder.getName() : dimension.getLoadKey();
        MutableComponent message = Component.empty();
        message.append(ModdedCommandFeedback.header("Iris Studio"));
        message.append(Component.literal("\n"));
        message.append(ModdedCommandFeedback.text("Opening studio for the \"", ModdedCommandFeedback.DARK_GREEN));
        message.append(ModdedCommandFeedback.text(dimension.getName(), ModdedCommandFeedback.PARAMETER_ALT));
        message.append(ModdedCommandFeedback.text("\" pack", ModdedCommandFeedback.DARK_GREEN));
        message.append(ModdedCommandFeedback.text(" (seed: " + seed + ")", ModdedCommandFeedback.VALUE));
        message.append(Component.literal("\n"));
        message.append(ModdedCommandFeedback.text("Pack ", ModdedCommandFeedback.DARK_GREEN));
        message.append(ModdedCommandFeedback.text(dimensionKey, ModdedCommandFeedback.PARAMETER));
        message.append(ModdedCommandFeedback.text(" is ready at ", ModdedCommandFeedback.DESCRIPTION));
        message.append(ModdedCommandFeedback.text(folder.getAbsolutePath(), ModdedCommandFeedback.VALUE));
        message.append(Component.literal("\n"));
        message.append(ModdedCommandFeedback.text("Modded servers cannot create Bukkit's temporary studio world at runtime; edit this pack directly, then use the matching world/datapack workflow or ", ModdedCommandFeedback.DESCRIPTION));
        message.append(ModdedCommandFeedback.button("/iris regen", "/iris regen", "Regenerate nearby chunks after editing this pack", false));
        message.append(ModdedCommandFeedback.text(" in an Iris dimension.", ModdedCommandFeedback.DESCRIPTION));
        message.append(Component.literal("\n"));
        message.append(ModdedCommandFeedback.button("Validate", "/iris pack validate " + dimensionKey, "Validate this pack before loading it", true));
        message.append(ModdedCommandFeedback.text("  ", ModdedCommandFeedback.OPTIONAL));
        message.append(ModdedCommandFeedback.button("World Help", "/iris world", "Open Iris world command help", true));
        message.append(ModdedCommandFeedback.text("  ", ModdedCommandFeedback.OPTIONAL));
        message.append(ModdedCommandFeedback.button("Package", "/iris studio package " + dimensionKey, "Package this dimension", false));
        message.append(Component.literal("\n"));
        message.append(ModdedCommandFeedback.footer());
        return message;
    }

    private static int version(CommandSourceStack source, String pack) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }
        IrisData data = IrisData.get(folder);
        IrisDimension dimension = data.getDimensionLoader().load(folder.getName());
        if (dimension == null) {
            IrisModdedCommands.fail(source, "Pack '" + folder.getName() + "' has no dimensions/" + folder.getName() + ".json");
            return 0;
        }
        IrisModdedCommands.ok(source, "The \"" + dimension.getName() + "\" pack has version: " + dimension.getVersion());
        return 1;
    }

    private static int create(CommandSourceStack source, String nameRaw, String template) {
        String name = nameRaw.toLowerCase(Locale.ROOT);
        if (!PROJECT_NAME.matcher(name).matches()) {
            IrisModdedCommands.fail(source, "Invalid project name '" + nameRaw + "' (allowed: a-z, 0-9, _ and -)");
            return 0;
        }
        File packsRoot = ModdedPackCommands.packsRoot();
        File target = new File(packsRoot, name);
        if (target.exists()) {
            IrisModdedCommands.fail(source, "Pack '" + name + "' already exists at " + target.getAbsolutePath());
            return 0;
        }
        MinecraftServer server = source.getServer();
        IrisModdedCommands.ok(source, "Creating project '" + name + "' from template '" + template + "'...");
        Thread thread = new Thread(() -> {
            try {
                File templateFolder = new File(packsRoot, template);
                if (!new File(templateFolder, "dimensions/" + template + ".json").isFile()) {
                    server.execute(() -> IrisModdedCommands.ok(source, "Template '" + template + "' is not installed; downloading IrisDimensions/" + template + "..."));
                    boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), template, "master",
                            (String line) -> server.execute(() -> IrisModdedCommands.ok(source, line)));
                    if (!installed || !new File(templateFolder, "dimensions/" + template + ".json").isFile()) {
                        server.execute(() -> IrisModdedCommands.fail(source, "Template '" + template + "' could not be downloaded; install a pack with dimensions/" + template + ".json first."));
                        return;
                    }
                }
                copyProject(templateFolder, target, template, name);
                server.execute(() -> {
                    IrisModdedCommands.ok(source, "Created project '" + name + "' at " + target.getAbsolutePath());
                    IrisModdedCommands.ok(source, "Edit dimensions/" + name + ".json and the rest of the pack, then create a world with it. VSCode workspaces are generated by the Bukkit studio toolchain only.");
                });
            } catch (Throwable e) {
                LOGGER.error("Iris studio create failed for {}", name, e);
                server.execute(() -> IrisModdedCommands.fail(source, "Project creation failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            }
        }, "Iris Studio Create");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static void copyProject(File templateFolder, File target, String template, String name) throws IOException {
        Path source = templateFolder.toPath();
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                String relative = source.relativize(path).toString();
                if (relative.isEmpty() || relative.equals(".git") || relative.startsWith(".git" + File.separator) || relative.endsWith(".code-workspace")) {
                    continue;
                }
                Path destination = target.toPath().resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        File oldDimension = new File(target, "dimensions/" + template + ".json");
        File newDimension = new File(target, "dimensions/" + name + ".json");
        if (oldDimension.isFile()) {
            Files.copy(oldDimension.toPath(), newDimension.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.delete(oldDimension.toPath());
        }
        if (newDimension.isFile()) {
            JSONObject json = new JSONObject(IO.readAll(newDimension));
            if (json.has("name")) {
                json.put("name", Form.capitalizeWords(name.replaceAll("\\Q-\\E", " ")));
                IO.writeAll(newDimension, json.toString(4));
            }
        }
    }

    private static int pkg(CommandSourceStack source, String pack) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        String dimKey = folder.getName();
        IrisModdedCommands.ok(source, "Packaging dimension '" + dimKey + "'...");
        Thread thread = new Thread(() -> {
            try {
                File result = compilePackage(folder, dimKey);
                server.execute(() -> IrisModdedCommands.ok(source, "Package compiled: " + result.getAbsolutePath()));
            } catch (Throwable e) {
                LOGGER.error("Iris package failed for {}", dimKey, e);
                server.execute(() -> IrisModdedCommands.fail(source, "Packaging failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            }
        }, "Iris Studio Package");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static File compilePackage(File packFolder, String dimKey) throws IOException {
        IrisData dm = IrisData.get(packFolder);
        IrisDimension dimension = dm.getDimensionLoader().load(dimKey);
        if (dimension == null) {
            throw new IOException("Pack '" + dimKey + "' has no dimensions/" + dimKey + ".json");
        }
        File exports = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("exports").toFile();
        File folder = new File(exports, dimension.getLoadKey());
        IO.delete(folder);
        folder.mkdirs();

        LinkedHashSet<String> regionKeys = new LinkedHashSet<>();
        LinkedHashSet<String> biomeKeys = new LinkedHashSet<>();
        LinkedHashSet<String> entityKeys = new LinkedHashSet<>();
        LinkedHashSet<String> spawnerKeys = new LinkedHashSet<>();
        LinkedHashSet<String> generatorKeys = new LinkedHashSet<>();
        LinkedHashSet<String> lootKeys = new LinkedHashSet<>();
        LinkedHashSet<String> objectKeys = new LinkedHashSet<>();

        regionKeys.addAll(dimension.getRegions());
        lootKeys.addAll(dimension.getLoot().getTables());
        spawnerKeys.addAll(dimension.getEntitySpawners());

        for (String regionKey : regionKeys) {
            IrisRegion region = dm.getRegionLoader().load(regionKey);
            if (region == null) {
                continue;
            }
            region.getAllBiomes(() -> dm).forEach((IrisBiome biome) -> {
                if (biome != null && biome.getLoadKey() != null) {
                    biomeKeys.add(biome.getLoadKey());
                }
            });
            lootKeys.addAll(region.getLoot().getTables());
            spawnerKeys.addAll(region.getEntitySpawners());
        }
        for (String biomeKey : biomeKeys) {
            IrisBiome biome = dm.getBiomeLoader().load(biomeKey);
            if (biome == null) {
                continue;
            }
            biome.getGenerators().forEach((IrisBiomeGeneratorLink link) -> generatorKeys.add(link.getGenerator()));
            lootKeys.addAll(biome.getLoot().getTables());
            spawnerKeys.addAll(biome.getEntitySpawners());
            for (IrisObjectPlacement placement : biome.getObjects()) {
                objectKeys.addAll(placement.getPlace());
            }
        }
        for (String spawnerKey : spawnerKeys) {
            IrisSpawner spawner = dm.getSpawnerLoader().load(spawnerKey);
            if (spawner == null) {
                continue;
            }
            spawner.getSpawns().forEach((IrisEntitySpawn spawn) -> entityKeys.add(spawn.getEntity()));
        }

        StringBuilder hashes = new StringBuilder();
        for (String objectKey : objectKeys) {
            try {
                File objectFile = dm.getObjectLoader().findFile(objectKey);
                IO.copyFile(objectFile, new File(folder, "objects/" + objectKey + ".iob"));
                hashes.append(IO.hash(objectFile));
            } catch (Throwable e) {
                LOGGER.error("Iris package failed to copy object {}", objectKey, e);
            }
        }

        hashes.append(copyJson(folder, "dimensions", dimension.getLoadKey(), dm.getDimensionLoader().findFile(dimension.getLoadKey())));
        for (String key : generatorKeys) {
            hashes.append(copyJson(folder, "generators", key, dm.getGeneratorLoader().findFile(key)));
        }
        for (String key : regionKeys) {
            hashes.append(copyJson(folder, "regions", key, dm.getRegionLoader().findFile(key)));
        }
        for (String key : dm.getBlockLoader().getPossibleKeys()) {
            hashes.append(copyJson(folder, "blocks", key, dm.getBlockLoader().findFile(key)));
        }
        for (String key : biomeKeys) {
            hashes.append(copyJson(folder, "biomes", key, dm.getBiomeLoader().findFile(key)));
        }
        for (String key : entityKeys) {
            hashes.append(copyJson(folder, "entities", key, dm.getEntityLoader().findFile(key)));
        }
        for (String key : lootKeys) {
            hashes.append(copyJson(folder, "loot", key, dm.getLootLoader().findFile(key)));
        }

        JSONObject meta = new JSONObject();
        meta.put("hash", IO.hash(hashes.toString()));
        meta.put("time", M.ms());
        meta.put("version", dimension.getVersion());
        IO.writeAll(new File(folder, "package.json"), meta.toString(0));

        File output = new File(exports, dimension.getLoadKey() + ".iris");
        ZipUtil.pack(folder, output, 9);
        IO.delete(folder);
        return output;
    }

    private static String copyJson(File folder, String category, String key, File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            String json = new JSONObject(IO.readAll(file)).toString(0);
            IO.writeAll(new File(folder, category + "/" + key + ".json"), json);
            return IO.hash(json);
        } catch (Throwable e) {
            LOGGER.error("Iris package failed to write {}/{}", category, key, e);
            return "";
        }
    }

    private static int regions(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players (sampling is centered on your position).");
            return 0;
        }
        Engine engine = IrisModdedCommands.engineFor(source.getLevel());
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        MinecraftServer server = source.getServer();
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        IrisModdedCommands.ok(source, "Sampling region distribution in " + (radius * 2) + "x" + (radius * 2) + " chunks around you...");
        Thread thread = new Thread(() -> {
            try {
                int diameter = radius * 2;
                int totalTasks = diameter * diameter;
                KMap<String, AtomicInteger> counts = new KMap<>();
                engine.getDimension().getRegions().forEach((String key) -> counts.put(key, new AtomicInteger(0)));
                MultiBurst burst = new MultiBurst("Region Sampler");
                BurstExecutor executor = burst.burst(totalTasks);
                new Spiraler(diameter, diameter, (int x, int z) -> executor.queue(() -> {
                    IrisRegion region = engine.getRegion((x << 4) + 8, (z << 4) + 8);
                    counts.computeIfAbsent(region.getLoadKey(), (String key) -> new AtomicInteger(0)).incrementAndGet();
                })).setOffset(blockX, blockZ).drain();
                executor.complete();
                burst.close();
                server.execute(() -> counts.forEach((String key, AtomicInteger count) -> {
                    IrisRegion region = engine.getData().getRegionLoader().load(key);
                    String rarity = region == null ? "?" : String.valueOf(region.getRarity());
                    IrisModdedCommands.ok(source, key + ": rarity=" + rarity + " / " + Form.f((double) count.get() / totalTasks * 100, 2) + "%");
                }));
            } catch (Throwable e) {
                LOGGER.error("Iris region sampling failed", e);
                server.execute(() -> IrisModdedCommands.fail(source, "Region sampling failed: " + e.getClass().getSimpleName()));
            }
        }, "Iris Region Sampler");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }
}
