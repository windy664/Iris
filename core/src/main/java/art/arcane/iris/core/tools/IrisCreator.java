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

package art.arcane.iris.core.tools;

import com.google.common.util.concurrent.AtomicDouble;
import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisRuntimeSchedulerMode;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.WorldLifecycleCaller;
import art.arcane.iris.core.lifecycle.WorldLifecycleRequest;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.service.BoardSVC;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import io.papermc.lib.PaperLib;
import lombok.Data;
import lombok.experimental.Accessors;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.world.TimeSkipEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static art.arcane.iris.util.common.misc.ServerProperties.BUKKIT_YML;

/**
 * Makes it a lot easier to setup an engine, world, studio or whatever
 */
@Data
@Accessors(fluent = true, chain = true)
public class IrisCreator {
    /**
     * Specify an area to pregenerate during creation
     */
    private PregenTask pregen;
    /**
     * Specify a sender to get updates & progress info + tp when world is created.
     */
    private VolmitSender sender;
    /**
     * The seed to use for this generator
     */
    private long seed = 1337;
    /**
     * The dimension to use. This can be any online dimension, or a dimension in the
     * packs folder
     */
    private String dimension = IrisSettings.get().getGenerator().getDefaultWorldType();
    /**
     * The name of this world.
     */
    private String name = "irisworld";
    /**
     * Studio mode makes the engine hotloadable and uses the dimension in
     * your Iris/packs folder instead of copying the dimension files into
     * the world itself. Studio worlds are deleted when they are unloaded.
     */
    private boolean studio = false;
    /**
     * Benchmark mode
     */
    private boolean benchmark = false;
    private BiConsumer<Double, String> studioProgressConsumer;

    public static boolean removeFromBukkitYml(String name) throws IOException {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        ConfigurationSection section = yml.getConfigurationSection("worlds");
        if (section == null) {
            return false;
        }
        section.set(name, null);
        if (section.getValues(false).keySet().stream().noneMatch(k -> section.get(k) != null)) {
            yml.set("worlds", null);
        }
        yml.save(BUKKIT_YML);
        return true;
    }
    public static boolean worldLoaded(){
        return true;
    }

    /**
     * Create the IrisAccess (contains the world)
     *
     * @return the IrisAccess
     * @throws IrisException shit happens
     */

    public World create() throws IrisException {
        if (Bukkit.isPrimaryThread()) {
            throw new IrisException("You cannot invoke create() on the main thread.");
        }

        reportStudioProgress(0.02D, "resolve_dimension");
        reportStudioProgress(0.08D, "resolve_dimension");
        IrisDimension d = IrisToolbelt.getDimension(dimension());

        if (d == null) {
            throw new IrisException("Dimension cannot be found null for id " + dimension());
        }

        if (sender == null)
            sender = Iris.getSender();

        reportStudioProgress(0.16D, "prepare_world_pack");
        if (!studio() || benchmark) {
            Iris.service(StudioSVC.class).installIntoWorld(sender, d.getLoadKey(), new File(Bukkit.getWorldContainer(), name()));
        }
        if (studio()) {
            IrisRuntimeSchedulerMode runtimeSchedulerMode = IrisRuntimeSchedulerMode.resolve(IrisSettings.get().getPregen());
            Iris.info("Studio create scheduling: mode=" + runtimeSchedulerMode.name().toLowerCase(Locale.ROOT)
                    + ", regionizedRuntime=" + FoliaScheduler.isRegionizedRuntime(Bukkit.getServer()));
        }

        reportStudioProgress(0.28D, "install_datapacks");
        AtomicDouble pp = new AtomicDouble(0);
        AtomicBoolean done = new AtomicBoolean(false);
        WorldCreator wc = new IrisWorldCreator()
                .dimension(dimension)
                .name(name)
                .seed(seed)
                .studio(studio)
                .create();
        if (!studio()) {
            IrisWorlds.get().put(name(), dimension());
        }
        ServerConfigurator.installDataPacksIfChanged(!studio());
        reportStudioProgress(0.40D, "install_datapacks");

        PlatformChunkGenerator access = (PlatformChunkGenerator) wc.generator();
        if (access == null) throw new IrisException("Access is null. Something bad happened.");
        AtomicInteger createProgressTask = startCreateProgressReporter(access, done);


        World world;
        reportStudioProgress(0.46D, "create_world");
        try {
            WorldLifecycleCaller callerKind = benchmark ? WorldLifecycleCaller.BENCHMARK : studio() ? WorldLifecycleCaller.STUDIO : WorldLifecycleCaller.CREATE;
            WorldLifecycleRequest request = WorldLifecycleRequest.fromCreator(wc, studio(), benchmark, callerKind);
            world = J.sfut(() -> INMS.get().createWorldAsync(wc, request))
                    .thenCompose(Function.identity())
                    .get();
        } catch (Throwable e) {
            done.set(true);
            cancelRepeatingTask(createProgressTask);
            if (J.isFolia() && containsCreateWorldUnsupportedOperation(e)) {
                throw new IrisException("Runtime world creation is blocked and the selected world lifecycle backend could not create the world.", e);
            }
            throw new IrisException("Failed to create world with backend family " + WorldLifecycleService.get().capabilities().serverFamily().id() + "!", e);
        }

        done.set(true);
        cancelRepeatingTask(createProgressTask);
        reportStudioProgress(0.86D, "create_world");

        if (!studio && !benchmark) {
            addToBukkitYml();
            J.s(() -> Iris.linkMultiverseCore.updateWorld(world, dimension));
        }

        if (pregen != null) {
            CompletableFuture<Boolean> ff = new CompletableFuture<>();

            IrisToolbelt.pregenerate(pregen, access)
                    .onProgress(pp::set)
                    .whenDone(() -> ff.complete(true));

            AtomicBoolean dx = new AtomicBoolean(false);
            AtomicInteger pregenProgressTask = startPregenProgressReporter(pp, dx);
            try {
                ff.get();
                dx.set(true);
                cancelRepeatingTask(pregenProgressTask);
            } catch (Throwable e) {
                dx.set(true);
                cancelRepeatingTask(pregenProgressTask);
                e.printStackTrace();
            }
        }
        return world;
    }

    private void reportStudioProgress(double progress, String stage) {
        BiConsumer<Double, String> consumer = studioProgressConsumer;
        if (consumer == null) {
            return;
        }

        double clamped = Math.max(0D, Math.min(1D, progress));
        try {
            consumer.accept(clamped, stage);
        } catch (Throwable e) {
            Iris.reportError("Studio progress consumer failed for world \"" + name() + "\".", e);
        }
    }

    private AtomicInteger startCreateProgressReporter(PlatformChunkGenerator access, AtomicBoolean done) {
        AtomicInteger taskId = new AtomicInteger(-1);
        if (benchmark) {
            return taskId;
        }

        IntSupplier generatedSupplier = () -> {
            if (access.getEngine() == null) {
                return 0;
            }
            return access.getEngine().getGenerated();
        };
        access.getSpawnChunks().whenComplete((required, throwable) -> {
            if (throwable != null) {
                Iris.reportError("Failed to resolve studio spawn chunk target for world \"" + name() + "\".", throwable);
                return;
            }

            if (done.get() || required == null || required <= 0) {
                return;
            }

            int interval = studioProgressConsumer != null || sender.isPlayer() ? 1 : 20;
            taskId.set(J.ar(() -> {
                if (done.get()) {
                    cancelRepeatingTask(taskId);
                    return;
                }

                int generated = generatedSupplier.getAsInt();
                if (generated >= required) {
                    cancelRepeatingTask(taskId);
                    return;
                }

                double progress = (double) generated / required;
                if (studioProgressConsumer != null) {
                    reportStudioProgress(0.40D + (0.42D * progress), "create_world");
                    return;
                }

                int percent = (int) Math.round(progress * 100.0D);
                int remaining = required - generated;
                if (sender.isPlayer()) {
                    int barWidth = 44;
                    int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * barWidth);
                    StringBuilder bar = new StringBuilder(barWidth * 3 + 4);
                    bar.append(C.DARK_GRAY).append("[");
                    for (int bi = 0; bi < barWidth; bi++) {
                        bar.append(bi < filled ? C.GREEN : C.DARK_GRAY).append("|");
                    }
                    bar.append(C.DARK_GRAY).append("]");
                    sender.sendAction(bar.toString() + C.GRAY + " " + C.YELLOW + percent + "%" + C.DARK_GRAY + " " + Form.f(generated) + "/" + Form.f(required) + " chunks");
                    return;
                }

                sender.sendMessage(C.GOLD + "Generating " + C.YELLOW + percent + "%" + C.GRAY + " " + Form.f(generated) + "/" + Form.f(required) + " chunks" + C.DARK_GRAY + " (" + remaining + " left)");
            }, interval));
        });
        return taskId;
    }

    private AtomicInteger startPregenProgressReporter(AtomicDouble progress, AtomicBoolean done) {
        AtomicInteger taskId = new AtomicInteger(-1);
        int interval = sender.isPlayer() ? 1 : 20;
        taskId.set(J.ar(() -> {
            if (done.get()) {
                cancelRepeatingTask(taskId);
                return;
            }

            double p = progress.get();
            int percent = (int) Math.round(p * 100.0D);
            if (sender.isPlayer()) {
                int barWidth = 44;
                int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, p)) * barWidth);
                StringBuilder bar = new StringBuilder(barWidth * 3 + 4);
                bar.append(C.DARK_GRAY).append("[");
                for (int bi = 0; bi < barWidth; bi++) {
                    bar.append(bi < filled ? C.GREEN : C.DARK_GRAY).append("|");
                }
                bar.append(C.DARK_GRAY).append("]");
                sender.sendAction(bar.toString() + C.GRAY + " " + C.YELLOW + percent + "%" + C.GRAY + " | " + C.WHITE + "Pregenerating");
                return;
            }

            sender.sendMessage(C.GOLD + "Pregenerating " + C.YELLOW + percent + "%");
        }, interval));
        return taskId;
    }

    private void cancelRepeatingTask(AtomicInteger taskId) {
        if (taskId == null) {
            return;
        }

        int id = taskId.getAndSet(-1);
        if (id >= 0) {
            J.car(id);
        }
    }

    private static boolean containsCreateWorldUnsupportedOperation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof UnsupportedOperationException) {
                for (StackTraceElement element : cursor.getStackTrace()) {
                    if ("org.bukkit.craftbukkit.CraftServer".equals(element.getClassName())
                            && "createWorld".equals(element.getMethodName())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void addToBukkitYml() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(BUKKIT_YML);
        String gen = "Iris:" + dimension;
        ConfigurationSection section = yml.contains("worlds") ? yml.getConfigurationSection("worlds") : yml.createSection("worlds");
        if (!section.contains(name)) {
            section.createSection(name).set("generator", gen);
            try {
                yml.save(BUKKIT_YML);
                Iris.info("Registered \"" + name + "\" in bukkit.yml");
            } catch (IOException e) {
                Iris.error("Failed to update bukkit.yml!");
                e.printStackTrace();
            }
        }
    }
}
