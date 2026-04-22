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

import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisPaperLikeBackendMode;
import art.arcane.iris.core.IrisRuntimeSchedulerMode;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.math.M;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncPregenMethod implements PregeneratorMethod {
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    private static final int ADAPTIVE_TIMEOUT_STEP = 3;
    private static final int ADAPTIVE_RECOVERY_INTERVAL = 8;
    private static final long CHUNK_CLEANUP_INTERVAL_MS = 15_000L;
    private static final long CHUNK_CLEANUP_MIN_AGE_MS = 5_000L;
    private final World world;
    private final IrisRuntimeSchedulerMode runtimeSchedulerMode;
    private final IrisPaperLikeBackendMode paperLikeBackendMode;
    private final boolean foliaRuntime;
    private final String backendMode;
    private final int workerPoolThreads;
    private final int runtimeCpuThreads;
    private final int effectiveWorkerThreads;
    private final int recommendedRuntimeConcurrencyCap;
    private final int configuredMaxConcurrency;
    private final Method directChunkAtAsyncUrgentMethod;
    private final Method directChunkAtAsyncMethod;
    private final String chunkAccessMode;
    private final Executor executor;
    private final Semaphore semaphore;
    private final int threads;
    private final int timeoutSeconds;
    private final int timeoutWarnIntervalMs;
    private final boolean urgent;
    private final Map<Chunk, Long> lastUse;
    private final AtomicInteger adaptiveInFlightLimit;
    private final int adaptiveMinInFlightLimit;
    private final AtomicInteger timeoutStreak = new AtomicInteger();
    private final AtomicLong lastTimeoutLogAt = new AtomicLong(0L);
    private final AtomicInteger suppressedTimeoutLogs = new AtomicInteger();
    private final AtomicLong lastAdaptiveLogAt = new AtomicLong(0L);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong lastProgressAt = new AtomicLong(M.ms());
    private final AtomicLong lastPermitWaitLog = new AtomicLong(0L);
    private final AtomicLong lastChunkCleanup = new AtomicLong(M.ms());
    private final Object permitMonitor = new Object();
    private volatile Engine metricsEngine;

    public AsyncPregenMethod(World world, int unusedThreads) {
        if (!PaperLib.isPaper()) {
            throw new UnsupportedOperationException("Cannot use PaperAsync on non paper!");
        }

        this.world = world;
        IrisSettings.IrisSettingsPregen pregen = IrisSettings.get().getPregen();
        this.runtimeSchedulerMode = IrisRuntimeSchedulerMode.resolve(pregen);
        this.foliaRuntime = runtimeSchedulerMode == IrisRuntimeSchedulerMode.FOLIA;
        ChunkAsyncMethodSelection chunkAsyncMethodSelection = resolveChunkAsyncMethodSelection(world);
        this.directChunkAtAsyncUrgentMethod = chunkAsyncMethodSelection.urgentMethod();
        this.directChunkAtAsyncMethod = chunkAsyncMethodSelection.standardMethod();
        this.chunkAccessMode = chunkAsyncMethodSelection.mode();
        int detectedWorkerPoolThreads = resolveWorkerPoolThreads();
        int detectedCpuThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int configuredWorldGenThreads = Math.max(1, IrisSettings.get().getConcurrency().getWorldGenThreads());
        int workerThreadsForCap = foliaRuntime
                ? resolveFoliaConcurrencyWorkerThreads(detectedWorkerPoolThreads, detectedCpuThreads, configuredWorldGenThreads)
                : resolvePaperLikeConcurrencyWorkerThreads(detectedWorkerPoolThreads, detectedCpuThreads, configuredWorldGenThreads);
        if (foliaRuntime) {
            this.paperLikeBackendMode = IrisPaperLikeBackendMode.AUTO;
            this.backendMode = "folia-region";
            this.executor = new FoliaRegionExecutor();
        } else {
            this.paperLikeBackendMode = resolvePaperLikeBackendMode(pregen);
            if (paperLikeBackendMode == IrisPaperLikeBackendMode.SERVICE) {
                this.executor = new ServiceExecutor();
                this.backendMode = "paper-service";
            } else {
                this.executor = new TicketExecutor();
                this.backendMode = "paper-ticket";
            }
        }
        int runtimeMaxConcurrency = foliaRuntime
                ? pregen.getFoliaMaxConcurrency()
                : pregen.getPaperLikeMaxConcurrency();
        int configuredThreads = applyRuntimeConcurrencyCap(
                runtimeMaxConcurrency,
                foliaRuntime,
                workerThreadsForCap
        );
        this.configuredMaxConcurrency = Math.max(1, pregen.getMaxConcurrency());
        this.threads = Math.max(1, configuredThreads);
        this.workerPoolThreads = detectedWorkerPoolThreads;
        this.runtimeCpuThreads = detectedCpuThreads;
        this.effectiveWorkerThreads = workerThreadsForCap;
        this.recommendedRuntimeConcurrencyCap = foliaRuntime
                ? computeFoliaRecommendedCap(workerThreadsForCap)
                : computePaperLikeRecommendedCap(workerThreadsForCap);
        this.semaphore = new Semaphore(this.threads, true);
        this.timeoutSeconds = pregen.getChunkLoadTimeoutSeconds();
        this.timeoutWarnIntervalMs = pregen.getTimeoutWarnIntervalMs();
        this.urgent = IrisSettings.get().getPregen().useHighPriority;
        this.lastUse = new ConcurrentHashMap<>();
        this.adaptiveInFlightLimit = new AtomicInteger(this.threads);
        this.adaptiveMinInFlightLimit = Math.max(4, Math.min(16, Math.max(1, this.threads / 4)));
    }

    private IrisPaperLikeBackendMode resolvePaperLikeBackendMode(IrisSettings.IrisSettingsPregen pregen) {
        IrisPaperLikeBackendMode configuredMode = pregen.getPaperLikeBackendMode();
        if (configuredMode != IrisPaperLikeBackendMode.AUTO) {
            return configuredMode;
        }

        return pregen.isUseVirtualThreads() ? IrisPaperLikeBackendMode.SERVICE : IrisPaperLikeBackendMode.TICKET;
    }

    private int resolveWorkerPoolThreads() {
        try {
            Class<?> moonriseCommonClass = Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon");
            java.lang.reflect.Field workerPoolField = moonriseCommonClass.getDeclaredField("WORKER_POOL");
            Object workerPool = workerPoolField.get(null);
            Object coreThreads = workerPool.getClass().getDeclaredMethod("getCoreThreads").invoke(workerPool);
            if (coreThreads instanceof Thread[] threadsArray) {
                return threadsArray.length;
            }
        } catch (Throwable ignored) {
        }

        return -1;
    }

    private void unloadAndSaveAllChunks() {
        if (foliaRuntime) {
            lastUse.clear();
            return;
        }

        if (lastUse.isEmpty()) {
            return;
        }

        try {
            J.sfut(() -> {
                if (world == null) {
                    Iris.warn("World was null somehow...");
                    return;
                }

                long minTime = M.ms() - 10_000;
                AtomicBoolean unloaded = new AtomicBoolean(false);
                lastUse.entrySet().removeIf(i -> {
                    final Chunk chunk = i.getKey();
                    final Long lastUseTime = i.getValue();
                    if (!chunk.isLoaded() || lastUseTime == null)
                        return true;
                    if (lastUseTime < minTime) {
                        chunk.unload();
                        unloaded.set(true);
                        return true;
                    }
                    return false;
                });
                if (unloaded.get()) {
                    world.save();
                }
            }).get();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void periodicChunkCleanup() {
        long now = M.ms();
        long lastCleanup = lastChunkCleanup.get();
        if (now - lastCleanup < CHUNK_CLEANUP_INTERVAL_MS) {
            return;
        }

        if (!lastChunkCleanup.compareAndSet(lastCleanup, now)) {
            return;
        }

        if (foliaRuntime) {
            int sizeBefore = lastUse.size();
            if (sizeBefore > 0) {
                lastUse.clear();
                Iris.info("Periodic chunk cleanup: cleared " + sizeBefore + " Folia chunk references");
            }
            return;
        }

        int sizeBefore = lastUse.size();
        if (sizeBefore == 0) {
            return;
        }

        long minTime = now - CHUNK_CLEANUP_MIN_AGE_MS;
        AtomicInteger removed = new AtomicInteger();
        lastUse.entrySet().removeIf(entry -> {
            Long lastUseTime = entry.getValue();
            if (lastUseTime == null || lastUseTime < minTime) {
                removed.incrementAndGet();
                return true;
            }
            return false;
        });

        int removedCount = removed.get();
        if (removedCount > 0) {
            Iris.info("Periodic chunk cleanup: removed " + removedCount + "/" + sizeBefore + " stale chunk references");
        }
    }

    private Chunk onChunkFutureFailure(int x, int z, Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        if (root instanceof java.util.concurrent.TimeoutException) {
            onTimeout(x, z);
        } else {
            Iris.warn("Failed async pregen chunk load at " + x + "," + z + ". " + metricsSnapshot());
        }

        Iris.reportError(throwable);
        return null;
    }

    private void onTimeout(int x, int z) {
        int streak = timeoutStreak.incrementAndGet();
        if (streak % ADAPTIVE_TIMEOUT_STEP == 0) {
            lowerAdaptiveInFlightLimit();
        }

        long now = M.ms();
        long last = lastTimeoutLogAt.get();
        if (now - last < timeoutWarnIntervalMs || !lastTimeoutLogAt.compareAndSet(last, now)) {
            suppressedTimeoutLogs.incrementAndGet();
            return;
        }

        int suppressed = suppressedTimeoutLogs.getAndSet(0);
        String suppressedText = suppressed <= 0 ? "" : " suppressed=" + suppressed;
        Iris.warn("Timed out async pregen chunk load at " + x + "," + z
                + " after " + timeoutSeconds + "s."
                + " adaptiveLimit=" + adaptiveInFlightLimit.get()
                + suppressedText + " " + metricsSnapshot());
    }

    private void onSuccess() {
        int streak = timeoutStreak.get();
        if (streak > 0) {
            int newStreak = Math.max(0, streak - 2);
            timeoutStreak.compareAndSet(streak, newStreak);
            if (newStreak > 0) {
                return;
            }
        }

        if ((completed.get() & (ADAPTIVE_RECOVERY_INTERVAL - 1)) == 0L) {
            raiseAdaptiveInFlightLimit();
        }
    }

    private void lowerAdaptiveInFlightLimit() {
        while (true) {
            int current = adaptiveInFlightLimit.get();
            if (current <= adaptiveMinInFlightLimit) {
                return;
            }

            int next = Math.max(adaptiveMinInFlightLimit, current - 1);
            if (adaptiveInFlightLimit.compareAndSet(current, next)) {
                logAdaptiveLimit("decrease", next);
                notifyPermitWaiters();
                return;
            }
        }
    }

    private void raiseAdaptiveInFlightLimit() {
        while (true) {
            int current = adaptiveInFlightLimit.get();
            if (current >= threads) {
                return;
            }

            int deficit = threads - current;
            int step = deficit > (threads / 2) ? Math.max(2, threads / 8) : 1;
            int next = Math.min(threads, current + step);
            if (adaptiveInFlightLimit.compareAndSet(current, next)) {
                logAdaptiveLimit("increase", next);
                notifyPermitWaiters();
                return;
            }
        }
    }

    private void logAdaptiveLimit(String mode, int value) {
        long now = M.ms();
        long last = lastAdaptiveLogAt.get();
        if (now - last < 5000L) {
            return;
        }

        if (lastAdaptiveLogAt.compareAndSet(last, now)) {
            Iris.info("Async pregen adaptive limit " + mode + " -> " + value + " " + metricsSnapshot());
        }
    }

    static int computePaperLikeRecommendedCap(int workerThreads) {
        int normalizedWorkers = Math.max(1, workerThreads);
        int recommendedCap = normalizedWorkers * 2;
        if (recommendedCap < 8) {
            return 8;
        }

        if (recommendedCap > 96) {
            return 96;
        }

        return recommendedCap;
    }

    static int resolvePaperLikeConcurrencyWorkerThreads(int detectedWorkerPoolThreads, int detectedCpuThreads, int configuredWorldGenThreads) {
        if (detectedWorkerPoolThreads > 0) {
            return detectedWorkerPoolThreads;
        }

        return Math.max(1, Math.max(detectedCpuThreads, configuredWorldGenThreads));
    }

    static int computeFoliaRecommendedCap(int workerThreads) {
        int normalizedWorkers = Math.max(1, workerThreads);
        int recommendedCap = normalizedWorkers * 4;
        if (recommendedCap < 64) {
            return 64;
        }

        if (recommendedCap > 192) {
            return 192;
        }

        return recommendedCap;
    }

    static int resolveFoliaConcurrencyWorkerThreads(int detectedWorkerPoolThreads, int detectedCpuThreads, int configuredWorldGenThreads) {
        return Math.max(detectedCpuThreads, Math.max(configuredWorldGenThreads, Math.max(1, detectedWorkerPoolThreads)));
    }

    static int applyRuntimeConcurrencyCap(int maxConcurrency, boolean foliaRuntime, int workerThreads) {
        int normalizedMaxConcurrency = Math.max(1, maxConcurrency);
        int recommendedCap = foliaRuntime
                ? computeFoliaRecommendedCap(workerThreads)
                : computePaperLikeRecommendedCap(workerThreads);
        return Math.min(normalizedMaxConcurrency, recommendedCap);
    }

    private String metricsSnapshot() {
        long stalledFor = Math.max(0L, M.ms() - lastProgressAt.get());
        return "world=" + world.getName()
                + " permits=" + semaphore.availablePermits() + "/" + threads
                + " adaptiveLimit=" + adaptiveInFlightLimit.get()
                + " inFlight=" + inFlight.get()
                + " submitted=" + submitted.get()
                + " completed=" + completed.get()
                + " failed=" + failed.get()
                + " stalledForMs=" + stalledFor;
    }

    private void markSubmitted() {
        submitted.incrementAndGet();
        inFlight.incrementAndGet();
    }

    private void markFinished(boolean success) {
        if (success) {
            completed.incrementAndGet();
            onSuccess();
        } else {
            failed.incrementAndGet();
        }

        lastProgressAt.set(M.ms());
        int after = inFlight.decrementAndGet();
        if (after < 0) {
            inFlight.compareAndSet(after, 0);
        }
        notifyPermitWaiters();
    }

    private void notifyPermitWaiters() {
        synchronized (permitMonitor) {
            permitMonitor.notifyAll();
        }
    }

    private void recordAdaptiveWait(long waitedMs) {
        Engine engine = resolveMetricsEngine();
        if (engine != null) {
            engine.getMetrics().getPregenWaitAdaptive().put(waitedMs);
        }
    }

    private void recordPermitWait(long waitedMs) {
        Engine engine = resolveMetricsEngine();
        if (engine != null) {
            engine.getMetrics().getPregenWaitPermit().put(waitedMs);
        }
    }

    private void cleanupMantleChunk(int x, int z) {
        Engine engine = resolveMetricsEngine();
        if (engine != null) {
            try {
                engine.getMantle().forceCleanupChunk(x, z);
            } catch (Throwable ignored) {
            }
        }
    }

    private Engine resolveMetricsEngine() {
        Engine cachedEngine = metricsEngine;
        if (cachedEngine != null) {
            return cachedEngine;
        }

        if (!IrisToolbelt.isIrisWorld(world)) {
            return null;
        }

        try {
            Engine resolvedEngine = IrisToolbelt.access(world).getEngine();
            if (resolvedEngine != null) {
                metricsEngine = resolvedEngine;
            }
            return resolvedEngine;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void logPermitWaitIfNeeded(int x, int z, long waitedMs) {
        long now = M.ms();
        long last = lastPermitWaitLog.get();
        if (now - last < 5000L) {
            return;
        }

        if (lastPermitWaitLog.compareAndSet(last, now)) {
            Iris.warn("Async pregen waiting for permit at chunk " + x + "," + z + " waitedMs=" + waitedMs + " " + metricsSnapshot());
        }
    }

    @Override
    public void init() {
        Iris.info("Async pregen init: world=" + world.getName()
                + ", mode=" + runtimeSchedulerMode.name().toLowerCase(Locale.ROOT)
                + ", backend=" + backendMode
                + ", chunkAccess=" + chunkAccessMode
                + ", threads=" + threads
                + ", adaptiveLimit=" + adaptiveInFlightLimit.get()
                + ", workerPoolThreads=" + workerPoolThreads
                + ", cpuThreads=" + runtimeCpuThreads
                + ", effectiveWorkerThreads=" + effectiveWorkerThreads
                + ", maxConcurrency=" + configuredMaxConcurrency
                + ", recommendedCap=" + recommendedRuntimeConcurrencyCap
                + ", urgent=" + urgent
                + ", timeout=" + timeoutSeconds + "s");
        unloadAndSaveAllChunks();
        increaseWorkerThreads();
    }

    @Override
    public String getMethod(int x, int z) {
        return "Async";
    }

    @Override
    public boolean isAsyncChunkMode() {
        return true;
    }

    @Override
    public void close() {
        semaphore.acquireUninterruptibly(threads);
        unloadAndSaveAllChunks();
        executor.shutdown();
        resetWorkerThreads();
    }

    @Override
    public void save() {
        unloadAndSaveAllChunks();
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
    public void generateChunk(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        periodicChunkCleanup();
        try {
            long waitStart = M.ms();
            synchronized (permitMonitor) {
                while (inFlight.get() >= adaptiveInFlightLimit.get()) {
                    long waited = Math.max(0L, M.ms() - waitStart);
                    logPermitWaitIfNeeded(x, z, waited);
                    permitMonitor.wait(500L);
                }
            }
            long adaptiveWait = Math.max(0L, M.ms() - waitStart);
            if (adaptiveWait > 0L) {
                recordAdaptiveWait(adaptiveWait);
            }

            long permitWaitStart = M.ms();
            while (!semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                logPermitWaitIfNeeded(x, z, Math.max(0L, M.ms() - waitStart));
            }
            long permitWait = Math.max(0L, M.ms() - permitWaitStart);
            if (permitWait > 0L) {
                recordPermitWait(permitWait);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        markSubmitted();
        executor.generate(x, z, listener);
    }

    private CompletableFuture<Chunk> requestChunkAsync(int x, int z) {
        Throwable failure = null;

        if (directChunkAtAsyncUrgentMethod != null) {
            try {
                return invokeChunkFuture(directChunkAtAsyncUrgentMethod, x, z, true, urgent);
            } catch (Throwable e) {
                failure = e;
            }
        }

        if (directChunkAtAsyncMethod != null) {
            try {
                return invokeChunkFuture(directChunkAtAsyncMethod, x, z, true, urgent);
            } catch (Throwable e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }

        try {
            CompletableFuture<Chunk> future = PaperLib.getChunkAtAsync(world, x, z, true, urgent);
            if (future != null) {
                return future;
            }
        } catch (Throwable e) {
            if (failure == null) {
                failure = e;
            }
        }

        if (failure == null) {
            failure = new IllegalStateException("Chunk async access returned no future.");
        }

        return CompletableFuture.failedFuture(new IllegalStateException("Failed to request async chunk " + x + "," + z + " in world " + world.getName(), failure));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Chunk> invokeChunkFuture(Method method, int x, int z, boolean generate, boolean urgentRequest) throws Throwable {
        Object result;
        try {
            if (method.getParameterCount() == 4) {
                result = method.invoke(world, x, z, generate, urgentRequest);
            } else {
                result = method.invoke(world, x, z, generate);
            }
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }

        if (result instanceof CompletableFuture<?>) {
            return (CompletableFuture<Chunk>) result;
        }

        throw new IllegalStateException("Chunk async method returned a non-future result.");
    }

    private static ChunkAsyncMethodSelection resolveChunkAsyncMethodSelection(World world) {
        if (world == null) {
            return new ChunkAsyncMethodSelection(null, null, "paperlib");
        }

        Class<?> worldClass = world.getClass();
        Method urgentMethod = resolveChunkAsyncMethod(worldClass, int.class, int.class, boolean.class, boolean.class);
        Method standardMethod = resolveChunkAsyncMethod(worldClass, int.class, int.class, boolean.class);
        if (urgentMethod != null) {
            return new ChunkAsyncMethodSelection(urgentMethod, standardMethod, "world#getChunkAtAsync(int,int,boolean,boolean)");
        }
        if (standardMethod != null) {
            return new ChunkAsyncMethodSelection(null, standardMethod, "world#getChunkAtAsync(int,int,boolean)");
        }
        return new ChunkAsyncMethodSelection(null, null, "paperlib");
    }

    private static Method resolveChunkAsyncMethod(Class<?> worldClass, Class<?>... parameterTypes) {
        try {
            return worldClass.getMethod("getChunkAtAsync", parameterTypes);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            return World.class.getMethod("getChunkAtAsync", parameterTypes);
        } catch (NoSuchMethodException ignored) {
        }

        return null;
    }

    @Override
    public Mantle getMantle() {
        if (IrisToolbelt.isIrisWorld(world)) {
            return IrisToolbelt.access(world).getEngine().getMantle().getMantle();
        }

        return null;
    }

    public static void increaseWorkerThreads() {
        THREAD_COUNT.updateAndGet(i -> {
            if (i > 0) return 1;
            var adjusted = IrisSettings.get().getConcurrency().getWorldGenThreads();
            try {
                var field = Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon").getDeclaredField("WORKER_POOL");
                var pool = field.get(null);
                var threads = ((Thread[]) pool.getClass().getDeclaredMethod("getCoreThreads").invoke(pool)).length;
                if (threads >= adjusted) return 0;

                pool.getClass().getDeclaredMethod("adjustThreadCount", int.class).invoke(pool, adjusted);
                return threads;
            } catch (Throwable e) {
                Iris.warn("Failed to increase worker threads, if you are on paper or a fork of it please increase it manually to " + adjusted);
                Iris.warn("For more information see https://docs.papermc.io/paper/reference/global-configuration#chunk_system_worker_threads");
                if (e instanceof InvocationTargetException) {
                    Iris.reportError(e);
                    e.printStackTrace();
                }
            }
            return 0;
        });
    }

    public static void resetWorkerThreads() {
        THREAD_COUNT.updateAndGet(i -> {
            if (i == 0) return 0;
            try {
                var field = Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon").getDeclaredField("WORKER_POOL");
                var pool = field.get(null);
                var method = pool.getClass().getDeclaredMethod("adjustThreadCount", int.class);
                method.invoke(pool, i);
                return 0;
            } catch (Throwable e) {
                Iris.reportError(e);
                Iris.error("Failed to reset worker threads");
                e.printStackTrace();
            }
            return i;
        });
    }

    private interface Executor {
        void generate(int x, int z, PregenListener listener);
        default void shutdown() {}
    }

    private class FoliaRegionExecutor implements Executor {
        @Override
        public void generate(int x, int z, PregenListener listener) {
            try {
                requestChunkAsync(x, z)
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .whenComplete((chunk, throwable) -> completeFoliaChunk(x, z, listener, chunk, throwable));
                return;
            } catch (Throwable ignored) {
            }

            if (!J.runRegion(world, x, z, () -> requestChunkAsync(x, z)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .whenComplete((chunk, throwable) -> completeFoliaChunk(x, z, listener, chunk, throwable)))) {
                markFinished(false);
                semaphore.release();
                Iris.warn("Failed to schedule Folia region pregen task at " + x + "," + z + ". " + metricsSnapshot());
            }
        }

        private void completeFoliaChunk(int x, int z, PregenListener listener, Chunk chunk, Throwable throwable) {
            boolean success = false;
            try {
                if (throwable != null) {
                    onChunkFutureFailure(x, z, throwable);
                    return;
                }

                if (chunk == null) {
                    return;
                }

                listener.onChunkGenerated(x, z);
                cleanupMantleChunk(x, z);
                listener.onChunkCleaned(x, z);
                lastUse.put(chunk, M.ms());
                success = true;
            } catch (Throwable e) {
                Iris.reportError(e);
                e.printStackTrace();
            } finally {
                markFinished(success);
                semaphore.release();
            }
        }
    }

    private class ServiceExecutor implements Executor {
        private final ExecutorService service = IrisSettings.get().getPregen().isUseVirtualThreads() ?
                Executors.newVirtualThreadPerTaskExecutor() :
                new MultiBurst("Iris Async Pregen");

        public void generate(int x, int z, PregenListener listener) {
            service.submit(() -> {
                boolean success = false;
                try {
                    Chunk i = requestChunkAsync(x, z)
                            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                            .exceptionally(e -> onChunkFutureFailure(x, z, e))
                            .get();

                    if (i == null) {
                        return;
                    }

                    listener.onChunkGenerated(x, z);
                    cleanupMantleChunk(x, z);
                    listener.onChunkCleaned(x, z);
                    lastUse.put(i, M.ms());
                    success = true;
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    Iris.reportError(e);
                    e.printStackTrace();
                } finally {
                    markFinished(success);
                    semaphore.release();
                }
            });
        }

        @Override
        public void shutdown() {
            service.shutdown();
        }
    }

    private class TicketExecutor implements Executor {
        @Override
        public void generate(int x, int z, PregenListener listener) {
            requestChunkAsync(x, z)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(e -> onChunkFutureFailure(x, z, e))
                    .thenAccept(i -> {
                        boolean success = false;
                        try {
                            if (i == null) {
                                return;
                            }

                            listener.onChunkGenerated(x, z);
                            cleanupMantleChunk(x, z);
                            listener.onChunkCleaned(x, z);
                            lastUse.put(i, M.ms());
                            success = true;
                        } finally {
                            markFinished(success);
                            semaphore.release();
                        }
                    });
        }
    }

    private record ChunkAsyncMethodSelection(Method urgentMethod, Method standardMethod, String mode) {
    }
}
