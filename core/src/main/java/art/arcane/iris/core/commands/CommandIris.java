/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.commands;

import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.ChunkReplacementListener;
import art.arcane.iris.engine.platform.ChunkReplacementOptions;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorContext;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.DirectorHelp;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.iris.util.common.director.specialhandlers.NullablePlayerHandler;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.iris.util.common.parallel.SyncExecutor;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.iris.core.service.EditSVC.deletingWorld;
import static art.arcane.iris.util.common.misc.ServerProperties.BUKKIT_YML;
import static org.bukkit.Bukkit.getServer;

@Director(name = "iris", aliases = {"ir", "irs"}, description = "Basic Command")
public class CommandIris implements DirectorExecutor {
    @Director(description = "Show help tree for this command group", aliases = {"?"})
    public void help() {
        DirectorHelp.print(sender(), getClass());
    }

    @Director(description = "Download/update external datapack imports declared in pack dimensions (alias of /iris datapack ingest)", aliases = {"ingestpacks"})
    public void ingest(
            @Param(description = "Restart the server when new datapacks are installed (required for new structures to register and generate)", defaultValue = "false")
            boolean restart
    ) {
        VolmitSender ingestSender = sender();
        ingestSender.sendMessage(C.GRAY + "Starting datapack ingest...");
        J.a(() -> DatapackIngestService.ingestAll(ingestSender, restart));
    }

    private CommandStudio studio;
    private CommandPregen pregen;
    private CommandSettings settings;
    private CommandObject object;
    private CommandStructure structure;
    private CommandWhat what;
    private CommandEdit edit;
    private CommandDeveloper developer;
    private CommandPack pack;
    private CommandFind find;
    private CommandDatapack datapack;
    public static boolean worldCreation = false;
    private static final AtomicReference<Thread> mainWorld = new AtomicReference<>();
    String WorldEngine;
    String worldNameToCheck = "YourWorldName";
    VolmitSender sender = Iris.getSender();

    @Director(description = "Create a new world", aliases = {"+", "c"})
    public void create(
            @Param(aliases = "world-name", description = "The name of the world to create")
            String name,
            @Param(
                    aliases = {"dimension", "pack"},
                    description = "The dimension/pack to create the world with",
                    defaultValue = "default",
                    customHandler = PackDimensionTypeHandler.class
            )
            String type,
            @Param(description = "The seed to generate the world with", defaultValue = "1337")
            long seed,
            @Param(aliases = "main-world", description = "Whether or not to automatically use this world as the main world", defaultValue = "false")
            boolean main,
            @Param(aliases = {"remove-others", "removeothers"}, description = "When main-world is true, remove other Iris worlds from bukkit.yml and queue deletion on startup", defaultValue = "false")
            boolean removeOthers,
            @Param(aliases = {"remove-worlds", "removeworlds"}, description = "Comma-separated world names to remove from Iris control and delete on next startup (main-world only)", defaultValue = "none")
            String removeWorlds
    ) {
        if (name.equalsIgnoreCase("iris")) {
            sender().sendMessage(C.RED + "You cannot use the world name \"iris\" for creating worlds as Iris uses this directory for studio worlds.");
            sender().sendMessage(C.RED + "May we suggest the name \"IrisWorld\" instead?");
            return;
        }

        if (name.equalsIgnoreCase("benchmark")) {
            sender().sendMessage(C.RED + "You cannot use the world name \"benchmark\" for creating worlds as Iris uses this directory for Benchmarking Packs.");
            sender().sendMessage(C.RED + "May we suggest the name \"IrisWorld\" instead?");
            return;
        }

        if (new File(Bukkit.getWorldContainer(), name).exists()) {
            sender().sendMessage(C.RED + "That folder already exists!");
            return;
        }

        String resolvedType = type.equalsIgnoreCase("default")
                ? IrisSettings.get().getGenerator().getDefaultWorldType()
                : type;

        IrisDimension dimension = IrisToolbelt.getDimension(resolvedType);
        if (dimension == null) {
            sender().sendMessage(C.RED + "Could not find or download dimension \"" + resolvedType + "\".");
            sender().sendMessage(C.YELLOW + "Try one of: overworld, vanilla, flat, theend");
            sender().sendMessage(C.YELLOW + "Or download manually: /iris download IrisDimensions/" + resolvedType);
            return;
        }

        if (!main && (removeOthers || hasExplicitCleanupWorlds(removeWorlds))) {
            sender().sendMessage(C.YELLOW + "remove-others/remove-worlds only apply when main-world=true. Ignoring cleanup options.");
            removeOthers = false;
            removeWorlds = "none";
        }

        if (J.isFolia()) {
            if (stageFoliaWorldCreation(name, dimension, seed, main, removeOthers, removeWorlds)) {
                sender().sendMessage(C.GREEN + "World staging completed. Restart the server to generate/load \"" + name + "\".");
            }
            return;
        }

        try {
            worldCreation = true;
            IrisToolbelt.createWorld()
                    .dimension(dimension.getLoadKey())
                    .name(name)
                    .seed(seed)
                    .sender(sender())
                    .studio(false)
                    .create();
            if (main) {
                Runtime.getRuntime().addShutdownHook(mainWorld.updateAndGet(old -> {
                    if (old != null) Runtime.getRuntime().removeShutdownHook(old);
                    return new Thread(() -> updateMainWorld(name));
                }));
            }
        } catch (Throwable e) {
            sender().sendMessage(C.RED + "Exception raised during creation. See the console for more details.");
            Iris.reportError("Exception raised during world creation for \"" + name + "\".", e);
            worldCreation = false;
            return;
        }

        if (main && !applyMainWorldCleanup(name, removeOthers, removeWorlds)) {
            worldCreation = false;
            return;
        }

        worldCreation = false;
        sender().sendMessage(C.GREEN + "Successfully created your world!");
        if (main) sender().sendMessage(C.GREEN + "Your world will automatically be set as the main world when the server restarts.");
    }

    private boolean updateMainWorld(String newName) {
        try {
            File worlds = Bukkit.getWorldContainer();
            var data = ServerProperties.DATA;
            try (var in = new FileInputStream(ServerProperties.SERVER_PROPERTIES)) {
                data.load(in);
            }

            File oldWorldFolder = new File(worlds, ServerProperties.LEVEL_NAME);
            File newWorldFolder = new File(worlds, newName);
            if (!newWorldFolder.exists() && !newWorldFolder.mkdirs()) {
                Iris.warn("Could not create target main world folder: " + newWorldFolder.getAbsolutePath());
            }

            for (String sub : List.of("datapacks", "playerdata", "advancements", "stats")) {
                File source = new File(oldWorldFolder, sub);
                if (!source.exists()) {
                    continue;
                }

                IO.copyDirectory(source.toPath(), new File(newWorldFolder, sub).toPath());
            }

            data.setProperty("level-name", newName);
            try (var out = new FileOutputStream(ServerProperties.SERVER_PROPERTIES)) {
                data.store(out, null);
            }
            return true;
        } catch (Throwable e) {
            Iris.error("Failed to update server.properties main world to \"" + newName + "\"");
            Iris.reportError(e);
            return false;
        }
    }

    private boolean stageFoliaWorldCreation(String name, IrisDimension dimension, long seed, boolean main, boolean removeOthers, String removeWorlds) {
        sender().sendMessage(C.YELLOW + "Runtime world creation is disabled on Folia.");
        sender().sendMessage(C.YELLOW + "Preparing world files and bukkit.yml for next startup...");

        File worldFolder = new File(Bukkit.getWorldContainer(), name);
        IrisDimension installed = Iris.service(StudioSVC.class).installIntoWorld(sender(), dimension.getLoadKey(), worldFolder);
        if (installed == null) {
            sender().sendMessage(C.RED + "Failed to stage world files for dimension \"" + dimension.getLoadKey() + "\".");
            return false;
        }

        if (!registerWorldInBukkitYml(name, dimension.getLoadKey(), seed)) {
            return false;
        }

        if (main) {
            if (updateMainWorld(name)) {
                sender().sendMessage(C.GREEN + "Updated server.properties level-name to \"" + name + "\".");
            } else {
                sender().sendMessage(C.RED + "World was staged, but failed to update server.properties main world.");
                return false;
            }

            if (!applyMainWorldCleanup(name, removeOthers, removeWorlds)) {
                sender().sendMessage(C.RED + "World was staged, but failed to apply main-world cleanup options.");
                return false;
            }
        }

        sender().sendMessage(C.GREEN + "Staged Iris world \"" + name + "\" with generator Iris:" + dimension.getLoadKey() + " and seed " + seed + ".");
        if (main) {
            sender().sendMessage(C.GREEN + "This world is now configured as main for next restart.");
        }
        return true;
    }

    private boolean registerWorldInBukkitYml(String worldName, String dimension, Long seed) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection worlds = yml.getConfigurationSection("worlds");
        if (worlds == null) {
            worlds = yml.createSection("worlds");
        }
        ConfigurationSection worldSection = worlds.getConfigurationSection(worldName);
        if (worldSection == null) {
            worldSection = worlds.createSection(worldName);
        }

        String generator = "Iris:" + dimension;
        worldSection.set("generator", generator);
        if (seed != null) {
            worldSection.set("seed", seed);
        }

        try {
            yml.save(BUKKIT_YML);
            Iris.info("Registered \"" + worldName + "\" in bukkit.yml");
            return true;
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to update bukkit.yml: " + e.getMessage());
            Iris.error("Failed to update bukkit.yml!");
            Iris.reportError(e);
            return false;
        }
    }

    private boolean applyMainWorldCleanup(String mainWorld, boolean removeOthers, String removeWorlds) {
        Set<String> targets = resolveCleanupTargets(mainWorld, removeOthers, removeWorlds);
        if (targets.isEmpty()) {
            return true;
        }

        sender().sendMessage(C.YELLOW + "Applying main-world cleanup for " + targets.size() + " world(s).");

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection worlds = yml.getConfigurationSection("worlds");

        Set<String> removedFromBukkit = new LinkedHashSet<>();
        Set<String> notRemoved = new LinkedHashSet<>();
        for (String target : targets) {
            String key = findWorldKeyIgnoreCase(worlds, target);
            if (key == null) {
                notRemoved.add(target);
                continue;
            }

            String generator = worlds.getString(key + ".generator");
            if (generator == null || !(generator.equalsIgnoreCase("iris") || generator.startsWith("Iris:"))) {
                notRemoved.add(key);
                continue;
            }

            worlds.set(key, null);
            removedFromBukkit.add(key);
        }

        try {
            if (worlds != null && worlds.getKeys(false).isEmpty()) {
                yml.set("worlds", null);
            }

            if (!removedFromBukkit.isEmpty()) {
                yml.save(BUKKIT_YML);
            }
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to update bukkit.yml while applying cleanup: " + e.getMessage());
            Iris.reportError(e);
            return false;
        }

        try {
            int queued = Iris.queueWorldDeletionOnStartup(targets);
            if (queued > 0) {
                sender().sendMessage(C.GREEN + "Queued " + queued + " world folder(s) for deletion on next startup.");
            } else {
                sender().sendMessage(C.YELLOW + "Cleanup queue already contained the requested world folder(s).");
            }
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to queue startup world deletions: " + e.getMessage());
            Iris.reportError(e);
            return false;
        }

        if (!removedFromBukkit.isEmpty()) {
            sender().sendMessage(C.GREEN + "Removed from Iris control in bukkit.yml: " + String.join(", ", removedFromBukkit));
        }

        if (!notRemoved.isEmpty()) {
            sender().sendMessage(C.YELLOW + "Skipped from bukkit.yml removal (not found or non-Iris generator): " + String.join(", ", notRemoved));
        }

        return true;
    }

    private Set<String> resolveCleanupTargets(String mainWorld, boolean removeOthers, String removeWorlds) {
        Set<String> targets = new LinkedHashSet<>();
        if (removeOthers) {
            IrisWorlds.readBukkitWorlds().keySet().stream()
                    .filter(world -> !world.equalsIgnoreCase(mainWorld))
                    .forEach(targets::add);
        }

        if (hasExplicitCleanupWorlds(removeWorlds)) {
            for (String raw : removeWorlds.split("[,;\\s]+")) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }

                if (raw.equalsIgnoreCase(mainWorld)) {
                    continue;
                }

                targets.add(raw.trim());
            }
        }

        return targets;
    }

    private static boolean hasExplicitCleanupWorlds(String removeWorlds) {
        if (removeWorlds == null) {
            return false;
        }

        String trimmed = removeWorlds.trim();
        return !trimmed.isEmpty() && !trimmed.equalsIgnoreCase("none");
    }

    private static String findWorldKeyIgnoreCase(ConfigurationSection worlds, String requested) {
        if (worlds == null || requested == null) {
            return null;
        }

        if (worlds.contains(requested)) {
            return requested;
        }

        for (String key : worlds.getKeys(false)) {
            if (key.equalsIgnoreCase(requested)) {
                return key;
            }
        }

        return null;
    }

    @Director(description = "Teleport to another world", aliases = {"tp"}, sync = true)
    public void teleport(
            @Param(description = "World to teleport to")
            World world,
            @Param(description = "Player to teleport", defaultValue = "---", customHandler = NullablePlayerHandler.class)
            Player player
    ) {
        if (player == null && sender().isPlayer())
            player = sender().player();

        final Player target = player;
        if (target == null) {
            sender().sendMessage(C.RED + "The specified player does not exist.");
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                target.teleport(world.getSpawnLocation());
                new VolmitSender(target).sendMessage(C.GREEN + "You have been teleported to " + world.getName() + ".");
            }
        }.runTask(Iris.instance);
    }

    @Director(description = "Print version information")
    public void version() {
        sender().sendMessage(C.GREEN + "Iris v" + Iris.instance.getDescription().getVersion() + " by Volmit Software");
    }

    /*
    /todo
    @Director(description = "Benchmark a pack", origin = DirectorOrigin.CONSOLE)
    public void packbenchmark(
            @Param(description = "Dimension to benchmark")
            IrisDimension type
    ) throws InterruptedException {

         BenchDimension = type.getLoadKey();

        IrisPackBenchmarking.runBenchmark();
    } */

    @Director(description = "Print world height information", origin = DirectorOrigin.PLAYER)
    public void height() {
        if (sender().isPlayer()) {
            sender().sendMessage(C.GREEN + "" + sender().player().getWorld().getMinHeight() + " to " + sender().player().getWorld().getMaxHeight());
            sender().sendMessage(C.GREEN + "Total Height: " + (sender().player().getWorld().getMaxHeight() - sender().player().getWorld().getMinHeight()));
        } else {
            World mainWorld = getServer().getWorlds().get(0);
            Iris.info(C.GREEN + "" + mainWorld.getMinHeight() + " to " + mainWorld.getMaxHeight());
            Iris.info(C.GREEN + "Total Height: " + (mainWorld.getMaxHeight() - mainWorld.getMinHeight()));
        }
    }

    @Director(description = "Check access of all worlds.", aliases = {"accesslist"})
    public void worlds() {
        KList<World> IrisWorlds = new KList<>();
        KList<World> BukkitWorlds = new KList<>();

        for (World w : Bukkit.getServer().getWorlds()) {
            try {
                Engine engine = IrisToolbelt.access(w).getEngine();
                if (engine != null) {
                    IrisWorlds.add(w);
                }
            } catch (Exception e) {
                BukkitWorlds.add(w);
            }
        }

        if (sender().isPlayer()) {
            sender().sendMessage(C.BLUE + "Iris Worlds: ");
            for (World IrisWorld : IrisWorlds.copy()) {
                sender().sendMessage(C.IRIS + "- " +IrisWorld.getName());
            }
            sender().sendMessage(C.GOLD + "Bukkit Worlds: ");
            for (World BukkitWorld : BukkitWorlds.copy()) {
                sender().sendMessage(C.GRAY + "- " +BukkitWorld.getName());
            }
        } else {
            Iris.info(C.BLUE + "Iris Worlds: ");
            for (World IrisWorld : IrisWorlds.copy()) {
                Iris.info(C.IRIS + "- " +IrisWorld.getName());
            }
            Iris.info(C.GOLD + "Bukkit Worlds: ");
            for (World BukkitWorld : BukkitWorlds.copy()) {
                Iris.info(C.GRAY + "- " +BukkitWorld.getName());
            }
            
        }
    }

    @Director(description = "Remove an Iris world", aliases = {"del", "rm", "delete"}, sync = true)
    public void remove(
            @Param(description = "The world to remove")
            World world,
            @Param(description = "Whether to also remove the folder (if set to false, just does not load the world)", defaultValue = "true")
            boolean delete
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Removing world: " + world.getName());

        if (!IrisToolbelt.evacuate(world)) {
            sender().sendMessage(C.RED + "Failed to evacuate world: " + world.getName());
            return;
        }

        if (!WorldLifecycleService.get().unload(world, false)) {
            sender().sendMessage(C.RED + "Failed to unload world: " + world.getName());
            return;
        }

        try {
            if (IrisToolbelt.removeWorld(world)) {
                sender().sendMessage(C.GREEN + "Successfully removed " + world.getName() + " from bukkit.yml");
            } else {
                sender().sendMessage(C.YELLOW + "Looks like the world was already removed from bukkit.yml");
            }
        } catch (IOException e) {
            sender().sendMessage(C.RED + "Failed to save bukkit.yml because of " + e.getMessage());
            Iris.reportError("Failed to remove world \"" + world.getName() + "\" from bukkit.yml.", e);
        }
        IrisToolbelt.evacuate(world, "Deleting world");
        deletingWorld = true;
        if (!delete) {
            deletingWorld = false;
            return;
        }
        VolmitSender sender = sender();
        J.a(() -> {
            int retries = 12;

            if (deleteDirectory(world.getWorldFolder())) {
                sender.sendMessage(C.GREEN + "Successfully removed world folder");
            } else {
                while(true){
                    if (deleteDirectory(world.getWorldFolder())){
                        sender.sendMessage(C.GREEN + "Successfully removed world folder");
                        break;
                    }
                    retries--;
                    if (retries == 0){
                        sender.sendMessage(C.RED + "Failed to remove world folder");
                        break;
                    }
                    J.sleep(3000);
                }
            }
            deletingWorld = false;
        });
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDirectory(children[i]);
                if (!success) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    @Director(description = "Toggle debug")
    public void debug() {
        boolean to = !IrisSettings.get().getGeneral().isDebug();
        IrisSettings.get().getGeneral().setDebug(to);
        IrisSettings.get().forceSave();
        sender().sendMessage(C.GREEN + "Set debug to: " + to);
    }

    @Director(description = "Download a project.", aliases = "dl")
    public void download(
            @Param(name = "pack", description = "The pack to download", defaultValue = "overworld", aliases = "project")
            String pack,
            @Param(name = "branch", description = "The branch to download from", defaultValue = "stable")
            String branch,
            @Param(name = "overwrite", description = "Whether or not to overwrite the pack with the downloaded one", aliases = "force", defaultValue = "false")
            boolean overwrite
    ) {
        sender().sendMessage(C.GREEN + "Downloading pack: " + pack + "/" + branch + (overwrite ? " overwriting" : ""));
        if (pack.equals("overworld")) {
            Iris.service(StudioSVC.class).downloadBranch(sender(), "IrisDimensions/overworld", "master", overwrite);
        } else {
            Iris.service(StudioSVC.class).downloadSearch(sender(), "IrisDimensions/" + pack + "/" + branch, overwrite);
        }
    }

    @Director(description = "Get metrics for your world", aliases = "measure", origin = DirectorOrigin.PLAYER)
    public void metrics() {
        if (!IrisToolbelt.isIrisWorld(world())) {
            sender().sendMessage(C.RED + "You must be in an Iris world");
            return;
        }
        sender().sendMessage(C.GREEN + "Sending metrics...");
        engine().printMetrics(sender());
    }

    @Director(description = "Reload configuration file (this is also done automatically)")
    public void reload() {
        IrisSettings.invalidate();
        IrisSettings.get();
        sender().sendMessage(C.GREEN + "Hotloaded settings");
    }


    @Director(description = "Unload an Iris World", origin = DirectorOrigin.PLAYER, sync = true)
    public void unloadWorld(
            @Param(description = "The world to unload")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Unloading world: " + world.getName());
        try {
            IrisToolbelt.evacuate(world);
            boolean unloaded = WorldLifecycleService.get().unload(world, false);
            if (unloaded) {
                sender().sendMessage(C.GREEN + "World unloaded successfully.");
            } else {
                sender().sendMessage(C.RED + "Failed to unload the world.");
            }
        } catch (Exception e) {
            sender().sendMessage(C.RED + "Failed to unload the world: " + e.getMessage());
            Iris.reportError("Failed to unload world \"" + world.getName() + "\".", e);
        }
    }

    @Director(description = "Load an Iris World", origin = DirectorOrigin.PLAYER, sync = true, aliases = {"import"})
    public void loadWorld(
            @Param(description = "The name of the world to load")
            String world
    ) {
        World worldloaded = Bukkit.getWorld(world);
        worldNameToCheck = world;
        boolean worldExists = doesWorldExist(worldNameToCheck);
        WorldEngine = world;

        if (!worldExists) {
            sender().sendMessage(C.YELLOW + world + " Doesnt exist on the server.");
            return;
        }

        String pathtodim = world + File.separator +"iris"+File.separator +"pack"+File.separator +"dimensions"+File.separator;
        File directory = new File(Bukkit.getWorldContainer(), pathtodim);

        String dimension = null;
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        String fileName = file.getName();
                        if (fileName.endsWith(".json")) {
                            dimension = fileName.substring(0, fileName.length() - 5);
                            sender().sendMessage(C.BLUE + "Generator: " + dimension);
                        }
                    }
                }
            }
        } else {
            sender().sendMessage(C.GOLD + world + " is not an iris world.");
            return;
        }

        if (dimension == null) {
            sender().sendMessage(C.RED + "Could not determine Iris dimension for " + world + ".");
            return;
        }

        sender().sendMessage(C.GREEN + "Loading world: " + world);

        if (!registerWorldInBukkitYml(world, dimension, null)) {
            return;
        }

        if (J.isFolia()) {
            sender().sendMessage(C.YELLOW + "Folia cannot load new worlds at runtime. Restart the server to load \"" + world + "\".");
            return;
        }

        Iris.instance.checkForBukkitWorlds(world::equals);
        sender().sendMessage(C.GREEN + world + " loaded successfully.");
    }
    @Director(description = "Evacuate an iris world", origin = DirectorOrigin.PLAYER, sync = true)
    public void evacuate(
            @Param(description = "Evacuate the world")
            World world
    ) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }
        sender().sendMessage(C.GREEN + "Evacuating world" + world.getName());
        IrisToolbelt.evacuate(world);
    }

    boolean doesWorldExist(String worldName) {
        File worldContainer = Bukkit.getWorldContainer();
        File worldDirectory = new File(worldContainer, worldName);
        return worldDirectory.exists() && worldDirectory.isDirectory();
    }

    public static class PackDimensionTypeHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> options = new LinkedHashSet<>();
            options.add("default");

            File packsFolder = Iris.instance.getDataFolder("packs");
            File[] packs = packsFolder.listFiles();
            if (packs != null) {
                for (File pack : packs) {
                    if (pack == null || !pack.isDirectory()) {
                        continue;
                    }

                    options.add(pack.getName());

                    try {
                        IrisData data = IrisData.get(pack);
                        for (String key : data.getDimensionLoader().getPossibleKeys()) {
                            options.add(key);
                        }
                    } catch (Throwable ex) {
                        Iris.warn("Failed to read dimension keys from pack %s: %s%s",
                                pack.getName(),
                                ex.getClass().getSimpleName(),
                                ex.getMessage() == null ? "" : " - " + ex.getMessage());
                        Iris.reportError(ex);
                    }
                }
            }

            return new KList<>(options);
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.trim().isEmpty()) {
                throw new DirectorParsingException("World type cannot be empty");
            }

            return in.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
