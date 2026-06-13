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

import art.arcane.iris.core.pregenerator.IrisPregenerator;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.math.Position2;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

public final class ModdedPregenJob implements PregenListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final AtomicReference<ModdedPregenJob> ACTIVE = new AtomicReference<>();

    private final String dimension;
    private final IrisPregenerator pregenerator;
    private volatile double chunksPerSecond;
    private volatile long generated;
    private volatile long totalChunks;
    private volatile long eta;
    private volatile long elapsed;
    private volatile String method = "Modded";

    private ModdedPregenJob(ServerLevel level, Engine engine, PregenTask task) {
        this.dimension = level.dimension().identifier().toString();
        this.pregenerator = new IrisPregenerator(task, new ModdedPregenMethod(level, engine), this);
    }

    public static boolean start(ServerLevel level, Engine engine, int radiusBlocks, int centerBlockX, int centerBlockZ) {
        if (ACTIVE.get() != null) {
            return false;
        }
        PregenTask task = PregenTask.builder()
                .gui(false)
                .center(new Position2(centerBlockX, centerBlockZ))
                .radiusX(radiusBlocks)
                .radiusZ(radiusBlocks)
                .build();
        ModdedPregenJob job = new ModdedPregenJob(level, engine, task);
        if (!ACTIVE.compareAndSet(null, job)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                job.pregenerator.start();
            } catch (Throwable e) {
                LOGGER.error("Iris pregen failed for {}", job.dimension, e);
            } finally {
                ACTIVE.compareAndSet(job, null);
            }
        }, "Iris Pregen");
        thread.setDaemon(true);
        thread.start();
        return true;
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
    }

    @Override
    public void onChunkGenerated(int x, int z, boolean cached) {
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
    }
}
