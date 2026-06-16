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

package art.arcane.iris.core.pregenerator.methods;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.M;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MedievalPregenMethod implements PregeneratorMethod {
    private final World world;
    private final KList<CompletableFuture<?>> futures;
    private final Map<Chunk, Long> lastUse;
    private final int maxFutures;
    private final AtomicBoolean directAsyncDisabled;
    private final AtomicBoolean prefetchDisabled;
    private final ExecutorService prefetchPool;
    private volatile Engine cachedEngine;
    private volatile boolean engineResolutionAttempted;

    public MedievalPregenMethod(World world) {
        this.world = world;
        futures = new KList<>();
        this.lastUse = new ConcurrentHashMap<>();
        int configuredThreads = IrisSettings.getThreadCount(IrisSettings.get().getConcurrency().getParallelism());
        this.maxFutures = J.isFolia() ? Math.max(2, Math.min(64, configuredThreads)) : Math.max(16, Math.min(128, configuredThreads * 4));
        this.directAsyncDisabled = new AtomicBoolean(false);
        this.prefetchDisabled = new AtomicBoolean(J.isFolia());
        int prefetchThreads = J.isFolia() ? 0 : Math.max(2, Math.min(16, configuredThreads));
        this.prefetchPool = prefetchThreads > 0 ? newPrefetchPool(prefetchThreads) : null;
    }

    private static ExecutorService newPrefetchPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Iris Medieval Prefetch " + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private Engine resolveEngine() {
        Engine cached = cachedEngine;
        if (cached != null) {
            return cached;
        }
        if (engineResolutionAttempted) {
            return null;
        }
        engineResolutionAttempted = true;
        try {
            if (!IrisToolbelt.isIrisWorld(world)) {
                return null;
            }
            cached = IrisToolbelt.access(world).getEngine();
            if (cached != null) {
                cachedEngine = cached;
            }
        } catch (Throwable ignored) {
        }
        return cached;
    }

    private void waitForChunks() {
        for (CompletableFuture<?> i : futures) {
            try {
                i.get();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        futures.clear();
    }

    private void unloadAndSaveAllChunks(boolean saveWorld) {
        if (J.isFolia()) {
            lastUse.clear();
            return;
        }

        try {
            J.sfut(() -> {
                if (world == null) {
                    IrisLogging.warn("World was null somehow...");
                    return;
                }

                for (Chunk i : new ArrayList<>(lastUse.keySet())) {
                    Long lastUseTime = lastUse.get(i);
                    if (lastUseTime != null && M.ms() - lastUseTime >= 10) {
                        i.unload();
                        lastUse.remove(i);
                    }
                }
                if (saveWorld) {
                    world.save();
                }
            }).get();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void init() {
        unloadAndSaveAllChunks(false);
    }

    @Override
    public void close() {
        waitForChunks();
        if (prefetchPool != null) {
            prefetchPool.shutdownNow();
        }
        unloadAndSaveAllChunks(true);
    }

    @Override
    public void save() {
        unloadAndSaveAllChunks(false);
    }

    @Override
    public boolean supportsRegions(int x, int z, PregenListener listener) {
        return false;
    }

    @Override
    public void generateRegion(int x, int z, PregenListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMethod(int x, int z) {
        return "Medieval";
    }

    @Override
    public void generateChunk(int x, int z, PregenListener listener) {
        if (futures.size() >= maxFutures) {
            waitForChunks();
        }

        listener.onChunkGenerating(x, z);
        if (J.isFolia()) {
            futures.add(PaperLib.getChunkAtAsync(world, x, z, true).thenAccept(c -> {
                if (c != null) {
                    lastUse.put(c, M.ms());
                }
                listener.onChunkGenerated(x, z);
                listener.onChunkCleaned(x, z);
            }));
            return;
        }

        futures.add(scheduleChunkLoad(x, z, listener));
    }

    private CompletableFuture<?> scheduleChunkLoad(int x, int z, PregenListener listener) {
        if (prefetchDisabled.get() || prefetchPool == null) {
            return runChunkLoad(x, z, listener);
        }

        Engine engine = resolveEngine();
        if (engine == null) {
            return runChunkLoad(x, z, listener);
        }

        CompletableFuture<Void> aggregate = new CompletableFuture<>();
        try {
            prefetchPool.submit(() -> {
                try {
                    prefetchMantle(engine, x, z);
                } catch (Throwable e) {
                    if (prefetchDisabled.compareAndSet(false, true)) {
                        IrisLogging.warn("Mantle prefetch failed at chunk " + x + "," + z + "; disabling prefetch for this pregen.");
                        IrisLogging.reportError(e);
                    }
                }

                CompletableFuture<?> chunkFuture = runChunkLoad(x, z, listener);
                chunkFuture.whenComplete((r, err) -> {
                    if (err != null) {
                        aggregate.completeExceptionally(err);
                    } else {
                        aggregate.complete(null);
                    }
                });
            });
        } catch (Throwable rejected) {
            if (prefetchDisabled.compareAndSet(false, true)) {
                IrisLogging.warn("Mantle prefetch pool rejected work; disabling prefetch.");
            }
            return runChunkLoad(x, z, listener);
        }
        return aggregate;
    }

    private void prefetchMantle(Engine engine, int chunkX, int chunkZ) {
        if (engine == null || engine.isClosing()) {
            return;
        }
        if (!engine.getDimension().isUseMantle()) {
            return;
        }

        ChunkContext context = new ChunkContext(chunkX, chunkZ, engine.getComplex());
        engine.generateMatter(chunkX, chunkZ, true, context);
    }

    private CompletableFuture<?> runChunkLoad(int x, int z, PregenListener listener) {
        return directAsyncDisabled.get() ? generateChunkSync(x, z, listener) : generateChunkDirectAsync(x, z, listener);
    }

    private CompletableFuture<?> generateChunkDirectAsync(int x, int z, PregenListener listener) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        J.a(() -> {
            try {
                loadChunk(x, z, listener);
                future.complete(null);
            } catch (Throwable error) {
                if (directAsyncDisabled.compareAndSet(false, true)) {
                    IrisLogging.warn("Direct async Spigot pregen chunk load failed at " + x + "," + z + "; falling back to sync chunk loads.");
                    IrisLogging.reportError(error);
                }

                try {
                    generateChunkSync(x, z, listener).get();
                    future.complete(null);
                } catch (Throwable fallbackError) {
                    future.completeExceptionally(fallbackError);
                }
            }
        });

        return future;
    }

    private CompletableFuture<?> generateChunkSync(int x, int z, PregenListener listener) {
        return J.sfut(() -> loadChunk(x, z, listener));
    }

    private void loadChunk(int x, int z, PregenListener listener) {
        Chunk chunk = world.getChunkAt(x, z);
        lastUse.put(chunk, M.ms());
        listener.onChunkGenerated(x, z);
        listener.onChunkCleaned(x, z);
    }

    @Override
    public Mantle getMantle() {
        if (IrisToolbelt.isIrisWorld(world)) {
            return IrisToolbelt.access(world).getEngine().getMantle().getMantle();
        }

        return null;
    }
}
