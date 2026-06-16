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
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.nbt.common.mca.NBTWorld;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.iris.util.project.hunk.storage.AtomicHunk;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.M;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SpigotDirectPregenMethod implements PregeneratorMethod {
    private static final int ADAPTIVE_TIMEOUT_STEP = 3;
    private static final int ADAPTIVE_RECOVERY_INTERVAL = 8;
    private static final long PERMIT_WAIT_NOTIFY_MS = 500L;

    private final World world;
    private final int threads;
    private final int timeoutSeconds;
    private final int timeoutWarnIntervalMs;
    private final int maxResidentTectonicPlates;
    private final int mantleBackpressureWaitMs;
    private final int mantleBackpressureTimeoutMs;

    private final Semaphore semaphore;
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
    private final Object permitMonitor = new Object();

    private final Set<Long> liveLoadedChunkKeys;
    private final ConcurrentHashMap<Long, Boolean> chunksWrittenDirect;
    private final AtomicBoolean writePathDisabled;
    private volatile Engine cachedEngine;
    private volatile Mantle cachedMantle;
    private volatile NBTWorld nbtWorld;
    private volatile MedievalPregenMethod fallback;

    public SpigotDirectPregenMethod(World world, int threads) {
        this.world = world;
        int configured = Math.max(1, threads);
        this.threads = Math.max(8, Math.min(32, configured));
        this.semaphore = new Semaphore(this.threads, true);
        this.adaptiveInFlightLimit = new AtomicInteger(this.threads);
        this.adaptiveMinInFlightLimit = Math.max(4, Math.min(16, Math.max(1, this.threads / 4)));

        IrisSettings.IrisSettingsPregen pregen = IrisSettings.get().getPregen();
        this.timeoutSeconds = pregen.getChunkLoadTimeoutSeconds();
        this.timeoutWarnIntervalMs = pregen.getTimeoutWarnIntervalMs();
        this.maxResidentTectonicPlates = pregen.getMaxResidentTectonicPlates();
        this.mantleBackpressureWaitMs = pregen.getMantleBackpressureWaitMs();
        this.mantleBackpressureTimeoutMs = pregen.getMantleBackpressureTimeoutMs();

        this.liveLoadedChunkKeys = ConcurrentHashMap.newKeySet();
        this.chunksWrittenDirect = new ConcurrentHashMap<>();
        this.writePathDisabled = new AtomicBoolean(false);
    }

    @Override
    public void init() {
        try {
            this.nbtWorld = new NBTWorld(world.getWorldFolder());
        } catch (Throwable e) {
            IrisLogging.error("SpigotDirect pregen could not open NBTWorld for " + world.getName() + "; disabling direct write path.");
            IrisLogging.reportError(e);
            writePathDisabled.set(true);
        }

        snapshotLoadedChunks();

        IrisLogging.info("SpigotDirect pregen init: world=" + world.getName()
                + ", threads=" + threads
                + ", adaptiveLimit=" + adaptiveInFlightLimit.get()
                + ", initialLoadedChunks=" + liveLoadedChunkKeys.size()
                + ", maxResidentTectonicPlates=" + maxResidentTectonicPlates
                + ", timeout=" + timeoutSeconds + "s");
    }

    @Override
    public void close() {
        semaphore.acquireUninterruptibly(threads);
        if (nbtWorld != null) {
            try {
                nbtWorld.flushNow();
                nbtWorld.close();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
        evictWrittenChunksFromServer();
        if (fallback != null) {
            try {
                fallback.close();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    @Override
    public void save() {
        if (nbtWorld != null) {
            try {
                nbtWorld.save();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
        if (fallback != null) {
            try {
                fallback.save();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
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
        return "SpigotDirect";
    }

    @Override
    public Mantle getMantle() {
        if (IrisToolbelt.isIrisWorld(world)) {
            return IrisToolbelt.access(world).getEngine().getMantle().getMantle();
        }
        return null;
    }

    @Override
    public void generateChunk(int x, int z, PregenListener listener) {
        listener.onChunkGenerating(x, z);
        enforceMantleBudget();

        long key = chunkKey(x, z);
        if (writePathDisabled.get() || nbtWorld == null || liveLoadedChunkKeys.contains(key) || world.isChunkLoaded(x, z)) {
            ensureFallback().generateChunk(x, z, listener);
            return;
        }

        Engine engine = resolveEngine();
        if (engine == null || !engine.getDimension().isUseMantle()) {
            ensureFallback().generateChunk(x, z, listener);
            return;
        }

        try {
            synchronized (permitMonitor) {
                while (inFlight.get() >= adaptiveInFlightLimit.get()) {
                    permitMonitor.wait(PERMIT_WAIT_NOTIFY_MS);
                }
            }
            while (!semaphore.tryAcquire(1, TimeUnit.SECONDS)) {
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        markSubmitted();
        MultiBurst.burst.lazy(() -> runDirect(engine, x, z, listener));
    }

    private void runDirect(Engine engine, int x, int z, PregenListener listener) {
        boolean success = false;
        try {
            int worldMinY = world.getMinHeight();
            int worldMaxY = world.getMaxHeight();
            int height = worldMaxY - worldMinY;

            PlatformBlockState air = IrisPlatforms.get().registries().air();
            Hunk<PlatformBlockState> blocks = new AirDefaultAtomicHunk(16, height, 16, air);
            Hunk<PlatformBiome> biomes = Hunk.newSynchronizedArrayHunk(16, height, 16);

            try {
                engine.generate(x << 4, z << 4, blocks, biomes, false);
            } catch (Throwable e) {
                handleFailure(x, z, e);
                return;
            }

            boolean wrote;
            try {
                wrote = INMS.get().writeChunkNbtDirect(nbtWorld, x, z, blocks, biomes);
            } catch (Throwable e) {
                handleFailure(x, z, e);
                return;
            }

            if (!wrote) {
                if (writePathDisabled.compareAndSet(false, true)) {
                    IrisLogging.warn("SpigotDirect NBT write returned false at chunk " + x + "," + z
                            + "; disabling direct path. Subsequent chunks will route through MedievalPregenMethod on the iterator thread.");
                }
                listener.onChunkGenerated(x, z);
                listener.onChunkCleaned(x, z);
                success = true;
                return;
            }

            chunksWrittenDirect.put(chunkKey(x, z), Boolean.TRUE);

            try {
                cleanupMantleChunk(x, z);
            } catch (Throwable ignored) {
            }

            listener.onChunkGenerated(x, z);
            listener.onChunkCleaned(x, z);
            success = true;
        } catch (Throwable e) {
            handleFailure(x, z, e);
        } finally {
            markFinished(success);
            semaphore.release();
        }
    }

    private void handleFailure(int x, int z, Throwable error) {
        IrisLogging.warn("SpigotDirect pregen failure at chunk " + x + "," + z + ". " + metricsSnapshot());
        IrisLogging.reportError(error);
        if (error != null) {
            error.printStackTrace(System.err);
        }
        if (writePathDisabled.compareAndSet(false, true)) {
            IrisLogging.warn("SpigotDirect: disabling direct write path after first failure. Subsequent chunks route through MedievalPregenMethod on the iterator thread.");
        }
        onTimeout(x, z);
    }

    private MedievalPregenMethod ensureFallback() {
        MedievalPregenMethod existing = fallback;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = fallback;
            if (existing == null) {
                existing = new MedievalPregenMethod(world);
                existing.init();
                fallback = existing;
            }
        }
        return existing;
    }

    private void snapshotLoadedChunks() {
        try {
            Set<Long> loaded = J.sfut(() -> {
                Set<Long> keys = new HashSet<>();
                if (world == null) {
                    return keys;
                }
                for (Chunk loadedChunk : world.getLoadedChunks()) {
                    keys.add(chunkKey(loadedChunk.getX(), loadedChunk.getZ()));
                }
                return keys;
            }).get();
            if (loaded != null) {
                liveLoadedChunkKeys.addAll(loaded);
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    private void evictWrittenChunksFromServer() {
        if (chunksWrittenDirect.isEmpty()) {
            return;
        }
        try {
            J.sfut(() -> {
                int evicted = 0;
                for (Long key : chunksWrittenDirect.keySet()) {
                    int x = keyX(key);
                    int z = keyZ(key);
                    if (!world.isChunkLoaded(x, z)) {
                        continue;
                    }
                    if (INMS.get().forceEvictChunk(world, x, z)) {
                        evicted++;
                    }
                }
                if (evicted > 0) {
                    IrisLogging.info("SpigotDirect: force-evicted " + evicted + " chunks loaded mid-pregen so the server reloads them from disk.");
                }
            }).get();
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyZ(long key) {
        return (int) (key & 0xffffffffL);
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
        IrisLogging.warn("SpigotDirect pregen failure cluster at " + x + "," + z
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
            IrisLogging.info("SpigotDirect pregen adaptive limit " + mode + " -> " + value + " (" + metricsSnapshot() + ")");
        }
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

    private void cleanupMantleChunk(int x, int z) {
        Engine engine = resolveEngine();
        if (engine != null) {
            try {
                engine.getMantle().forceCleanupChunk(x, z);
            } catch (Throwable ignored) {
            }
        }
    }

    private Engine resolveEngine() {
        Engine cached = cachedEngine;
        if (cached != null) {
            return cached;
        }

        if (!IrisToolbelt.isIrisWorld(world)) {
            return null;
        }

        try {
            Engine resolved = IrisToolbelt.access(world).getEngine();
            if (resolved != null) {
                cachedEngine = resolved;
            }
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Mantle resolveMantle() {
        Mantle cached = cachedMantle;
        if (cached != null) {
            return cached;
        }
        Mantle resolved = getMantle();
        if (resolved != null) {
            cachedMantle = resolved;
        }
        return resolved;
    }

    private void enforceMantleBudget() {
        int cap = maxResidentTectonicPlates;
        if (cap <= 0) {
            return;
        }

        Mantle mantle = resolveMantle();
        if (mantle == null) {
            return;
        }

        int hardCap = cap * 2;
        if (mantle.getLoadedRegionCount() <= hardCap) {
            return;
        }

        long waitStart = M.ms();
        long lastLog = 0L;
        while (mantle.getLoadedRegionCount() > hardCap) {
            mantle.trim(0L, 0);
            int freed = mantle.unloadTectonicPlate(0);
            int resident = mantle.getLoadedRegionCount();
            if (resident <= hardCap) {
                break;
            }

            long elapsed = M.ms() - waitStart;
            if (elapsed >= mantleBackpressureTimeoutMs) {
                IrisLogging.warn("SpigotDirect mantle backpressure exceeded " + mantleBackpressureTimeoutMs + "ms with " + resident
                        + " tectonic plates resident (hard cap " + hardCap + "); proceeding to avoid deadlock. "
                        + "Raise pregen.maxResidentTectonicPlates if this persists. " + metricsSnapshot());
                return;
            }

            long logNow = M.ms();
            if (logNow - lastLog >= 5_000L) {
                lastLog = logNow;
                IrisLogging.warn("SpigotDirect mantle backpressure: " + resident + " tectonic plates resident (hard cap " + hardCap
                        + "), freed " + freed + " last pass, waited " + elapsed + "ms.");
            }

            try {
                Thread.sleep(mantleBackpressureWaitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class AirDefaultAtomicHunk extends AtomicHunk<PlatformBlockState> {
        private final PlatformBlockState airDefault;

        AirDefaultAtomicHunk(int w, int h, int d, PlatformBlockState airDefault) {
            super(w, h, d);
            this.airDefault = airDefault;
        }

        @Override
        public PlatformBlockState getRaw(int x, int y, int z) {
            PlatformBlockState v = super.getRaw(x, y, z);
            return v != null ? v : airDefault;
        }
    }
}
