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

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ModdedPregenMethod implements PregeneratorMethod {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final TicketType PREGEN_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private final ServerLevel level;
    private final Engine engine;
    private final ModdedPregenMode mode;
    private final Semaphore semaphore;
    private final int permits;
    private final int timeoutSeconds;

    public ModdedPregenMethod(ServerLevel level, Engine engine) {
        this(level, engine, ModdedPregenMode.ASYNC);
    }

    public ModdedPregenMethod(ServerLevel level, Engine engine, ModdedPregenMode mode) {
        this.level = level;
        this.engine = engine;
        this.mode = mode;
        this.permits = Math.min(96, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        this.semaphore = new Semaphore(permits, true);
        this.timeoutSeconds = Math.max(120, IrisSettings.get().getPregen().getChunkLoadTimeoutSeconds());
    }

    @Override
    public void init() {
        LOGGER.info("Iris modded pregen init: dim={} mode={} inFlightCap={} timeout={}s",
                level.dimension().identifier(), mode, mode == ModdedPregenMode.ASYNC ? permits : 1, timeoutSeconds);
    }

    @Override
    public void close() {
        if (mode == ModdedPregenMode.ASYNC) {
            semaphore.acquireUninterruptibly(permits);
        }
        saveLevel();
    }

    @Override
    public void save() {
        saveLevel();
    }

    private void saveLevel() {
        level.getServer().execute(() -> level.save(null, false, false));
    }

    @Override
    public boolean supportsRegions(int x, int z, PregenListener listener) {
        return false;
    }

    @Override
    public String getMethod(int x, int z) {
        return "Modded";
    }

    @Override
    public boolean isAsyncChunkMode() {
        return mode == ModdedPregenMode.ASYNC;
    }

    @Override
    public void generateRegion(int x, int z, PregenListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void generateChunk(int x, int z, PregenListener listener) {
        if (mode == ModdedPregenMode.SYNC) {
            generateChunkSync(x, z, listener);
            return;
        }
        generateChunkAsync(x, z, listener);
    }

    private void generateChunkSync(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        ChunkPos pos = new ChunkPos(x, z);
        CompletableFuture<?> loadFuture = CompletableFuture
                .supplyAsync(() -> level.getChunkSource().addTicketAndLoadWithRadius(PREGEN_TICKET, pos, 0), level.getServer())
                .thenCompose((CompletableFuture<?> inner) -> inner);
        try {
            Object result = loadFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            if (result instanceof ChunkResult<?> chunkResult && !chunkResult.isSuccess()) {
                LOGGER.warn("Iris pregen chunk {},{} returned no chunk: {}", x, z, chunkResult.getError());
                return;
            }
            listener.onChunkGenerated(x, z);
            cleanupMantleChunk(x, z);
            listener.onChunkCleaned(x, z);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            LOGGER.warn("Iris pregen chunk {},{} failed: {}", x, z, e.toString());
        } finally {
            level.getServer().execute(() -> level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, pos, 0));
        }
    }

    private void generateChunkAsync(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        ChunkPos pos = new ChunkPos(x, z);
        CompletableFuture<?> loadFuture = CompletableFuture
                .supplyAsync(() -> level.getChunkSource().addTicketAndLoadWithRadius(PREGEN_TICKET, pos, 0), level.getServer())
                .thenCompose((CompletableFuture<?> inner) -> inner);

        loadFuture.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((Object result, Throwable error) -> {
            level.getServer().execute(() -> level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, pos, 0));
            try {
                if (error != null) {
                    LOGGER.warn("Iris pregen chunk {},{} failed: {}", x, z, error.toString());
                    return;
                }
                if (result instanceof ChunkResult<?> chunkResult && !chunkResult.isSuccess()) {
                    LOGGER.warn("Iris pregen chunk {},{} returned no chunk: {}", x, z, chunkResult.getError());
                    return;
                }
                listener.onChunkGenerated(x, z);
                cleanupMantleChunk(x, z);
                listener.onChunkCleaned(x, z);
            } finally {
                semaphore.release();
            }
        });
    }

    private void cleanupMantleChunk(int x, int z) {
        try {
            engine.getMantle().forceCleanupChunk(x, z);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Mantle getMantle() {
        return engine.getMantle().getMantle();
    }
}
