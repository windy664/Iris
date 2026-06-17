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

import art.arcane.iris.core.gui.PregenRenderSource;
import art.arcane.iris.core.gui.PregenRenderer;
import art.arcane.iris.core.pregenerator.IrisPregenerator;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.pregenerator.cache.PregenCache;
import art.arcane.iris.core.pregenerator.methods.CachedPregenMethod;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.math.Position2;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public final class ModdedPregenJob implements PregenListener, PregenRenderSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final AtomicReference<ModdedPregenJob> ACTIVE = new AtomicReference<>();
    private static final Color COLOR_GENERATING = new Color(0x66967f);
    private static final Color COLOR_GENERATED = new Color(0x65c295);

    private final String dimension;
    private final IrisPregenerator pregenerator;
    private final Engine engine;
    private final Position2 min;
    private final Position2 max;
    private final PregenRenderer renderer;
    private volatile double chunksPerSecond;
    private volatile long generated;
    private volatile long totalChunks;
    private volatile long eta;
    private volatile long elapsed;
    private volatile String method = "Modded";

    private ModdedPregenJob(MinecraftServer server, ServerLevel level, Engine engine, PregenTask task, boolean gui, ModdedPregenMode mode, boolean cached) {
        this.dimension = level.dimension().identifier().toString();
        this.engine = engine;
        this.min = new Position2(Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.max = new Position2(Integer.MIN_VALUE, Integer.MIN_VALUE);
        task.iterateAllChunks((int chunkX, int chunkZ) -> {
            min.setX(Math.min(chunkX, min.getX()));
            min.setZ(Math.min(chunkZ, min.getZ()));
            max.setX(Math.max(chunkX, max.getX()));
            max.setZ(Math.max(chunkZ, max.getZ()));
        });
        PregeneratorMethod baseMethod = new ModdedPregenMethod(level, engine, mode);
        PregeneratorMethod resolvedMethod = baseMethod;
        if (cached) {
            PregenCache cache = PregenCache.create(cacheDirectory(level)).sync();
            resolvedMethod = new CachedPregenMethod(baseMethod, cache);
        }
        this.method = mode == ModdedPregenMode.SYNC ? "Modded Sync" : "Modded";
        this.pregenerator = new IrisPregenerator(task, resolvedMethod, this);
        PregenRenderer openedRenderer = null;
        if (gui) {
            try {
                openedRenderer = PregenRenderer.open("Iris Pregen: " + dimension, this, ModdedPregenJob::pauseResume);
            } catch (Throwable e) {
                LOGGER.error("Iris pregen GUI failed to open for {}", dimension, e);
            }
        }
        this.renderer = openedRenderer;
    }

    private static File cacheDirectory(ServerLevel level) {
        File worldFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).toFile();
        return new File(worldFolder, "iris" + File.separator + "pregen");
    }

    public static boolean start(MinecraftServer server, ServerLevel level, Engine engine, int radiusBlocks, int centerBlockX, int centerBlockZ, boolean gui) {
        return start(server, level, engine, radiusBlocks, centerBlockX, centerBlockZ, gui, ModdedPregenMode.ASYNC, false);
    }

    public static boolean start(MinecraftServer server, ServerLevel level, Engine engine, int radiusBlocks, int centerBlockX, int centerBlockZ, boolean gui, ModdedPregenMode mode, boolean cached) {
        if (ACTIVE.get() != null) {
            return false;
        }
        PregenTask task = PregenTask.builder()
                .gui(false)
                .center(new Position2(centerBlockX, centerBlockZ))
                .radiusX(radiusBlocks)
                .radiusZ(radiusBlocks)
                .build();
        ModdedPregenJob job = new ModdedPregenJob(server, level, engine, task, gui, mode, cached);
        if (!ACTIVE.compareAndSet(null, job)) {
            job.closeRenderer();
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                job.pregenerator.start();
            } catch (Throwable e) {
                LOGGER.error("Iris pregen failed for {}", job.dimension, e);
            } finally {
                job.closeRenderer();
                ACTIVE.compareAndSet(job, null);
            }
        }, "Iris Pregen");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void closeRenderer() {
        if (renderer != null) {
            renderer.close();
        }
    }

    private void draw(int chunkX, int chunkZ, Color color) {
        if (renderer == null || !renderer.isVisibleFrame()) {
            return;
        }
        try {
            Color resolved = color;
            if (engine != null) {
                resolved = engine.draw((chunkX << 4) + 8, (chunkZ << 4) + 8);
            }
            renderer.submit(chunkX, chunkZ, resolved);
        } catch (Throwable e) {
            renderer.submit(chunkX, chunkZ, color);
        }
    }

    public static boolean stop() {
        ModdedPregenJob job = ACTIVE.get();
        if (job == null) {
            return false;
        }
        job.pregenerator.close();
        return true;
    }

    public static Boolean pauseResume() {
        ModdedPregenJob job = ACTIVE.get();
        if (job == null) {
            return null;
        }
        if (job.pregenerator.paused()) {
            job.pregenerator.resume();
            return Boolean.FALSE;
        }
        job.pregenerator.pause();
        return Boolean.TRUE;
    }

    public static String status() {
        ModdedPregenJob job = ACTIVE.get();
        if (job == null) {
            return null;
        }
        return job.statusText();
    }

    public static Component statusComponent() {
        ModdedPregenJob job = ACTIVE.get();
        if (job == null) {
            return null;
        }

        double percent = job.percent();
        MutableComponent status = Component.empty();
        status.append(ModdedCommandFeedback.header("Iris Pregen"));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("Dimension ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(job.dimension, ModdedCommandFeedback.PARAMETER_ALT));
        status.append(ModdedCommandFeedback.text(" · Method ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(job.method, ModdedCommandFeedback.PARAMETER));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.progressBar(percent, 32));
        status.append(ModdedCommandFeedback.text(" " + String.format("%.1f", percent) + "%", ModdedCommandFeedback.USAGE));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("Chunks ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.f(job.generated) + "/" + Form.f(job.totalChunks), ModdedCommandFeedback.VALUE));
        status.append(ModdedCommandFeedback.text(" · Speed ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.f((int) job.chunksPerSecond) + "/s", ModdedCommandFeedback.VALUE));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.text("ETA ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.duration(job.eta, 2), ModdedCommandFeedback.VALUE));
        status.append(ModdedCommandFeedback.text(" · Elapsed ", ModdedCommandFeedback.DARK_GREEN));
        status.append(ModdedCommandFeedback.text(Form.duration(job.elapsed, 2), ModdedCommandFeedback.VALUE));
        if (job.pregenerator.paused()) {
            status.append(ModdedCommandFeedback.text(" · PAUSED", ModdedCommandFeedback.REQUIRED, true, false));
        }
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.button("Pause/Resume", "/iris pregen pause", "Toggle pregeneration pause state", true));
        status.append(ModdedCommandFeedback.text("  ", ModdedCommandFeedback.OPTIONAL));
        status.append(ModdedCommandFeedback.button("Stop", "/iris pregen stop", "Finish the current region and stop pregeneration", true));
        status.append(Component.literal("\n"));
        status.append(ModdedCommandFeedback.footer());
        return status;
    }

    private String statusText() {
        double percent = percent();
        return "Pregen " + dimension + ": "
                + Form.f(generated) + "/" + Form.f(totalChunks)
                + " (" + String.format("%.1f", percent) + "%), "
                + Form.f((int) chunksPerSecond) + "/s"
                + ", ETA " + Form.duration(eta, 2)
                + ", elapsed " + Form.duration(elapsed, 2)
                + ", method " + method
                + (pregenerator.paused() ? ", PAUSED" : "");
    }

    private double percent() {
        long total = Math.max(1L, totalChunks);
        return ((double) generated / (double) total) * 100D;
    }

    @Override
    public void onTick(double chunksPerSecond, double chunksPerMinute, double regionsPerMinute, double percent, long generated, long totalChunks, long chunksRemaining, long eta, long elapsed, String method, boolean cached) {
        this.chunksPerSecond = chunksPerSecond;
        this.generated = generated;
        this.totalChunks = totalChunks;
        this.eta = eta;
        this.elapsed = elapsed;
        this.method = method;
    }

    @Override
    public void onChunkGenerating(int x, int z) {
        draw(x, z, COLOR_GENERATING);
    }

    @Override
    public void onChunkGenerated(int x, int z, boolean cached) {
        draw(x, z, COLOR_GENERATED);
    }

    @Override
    public void onRegionGenerated(int x, int z) {
    }

    @Override
    public void onRegionGenerating(int x, int z) {
    }

    @Override
    public void onChunkCleaned(int x, int z) {
    }

    @Override
    public void onRegionSkipped(int x, int z) {
    }

    @Override
    public void onNetworkStarted(int x, int z) {
    }

    @Override
    public void onNetworkFailed(int x, int z) {
    }

    @Override
    public void onNetworkReclaim(int revert) {
    }

    @Override
    public void onNetworkGeneratedChunk(int x, int z) {
    }

    @Override
    public void onNetworkDownloaded(int x, int z) {
    }

    @Override
    public void onClose() {
    }

    @Override
    public void onSaving() {
    }

    @Override
    public void onChunkExistsInRegionGen(int x, int z) {
        draw(x, z, COLOR_GENERATED);
    }

    @Override
    public Position2 min() {
        return min;
    }

    @Override
    public Position2 max() {
        return max;
    }

    @Override
    public String[] progress() {
        double percent = percent();
        return new String[]{
                (pregenerator.paused() ? "PAUSED " : "Generating ") + Form.f(generated) + " of " + Form.f(totalChunks)
                        + " (" + String.format("%.1f", percent) + "%)",
                "Speed: " + Form.f((int) chunksPerSecond) + " Chunks/s",
                Form.duration(eta, 2) + " Remaining (" + Form.duration(elapsed, 2) + " Elapsed)",
                "Dimension: " + dimension,
                "Method: " + method
        };
    }

    @Override
    public boolean paused() {
        return pregenerator.paused();
    }
}
