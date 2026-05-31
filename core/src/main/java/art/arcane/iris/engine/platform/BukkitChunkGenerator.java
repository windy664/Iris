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

package art.arcane.iris.engine.platform;

import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.IrisWorlds;
import art.arcane.iris.core.gui.PregeneratorJob;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.VanillaStructureMode;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.engine.object.StudioMode;
import art.arcane.iris.engine.platform.studio.StudioGenerator;
import art.arcane.iris.util.project.matter.TileWrapper;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.iris.util.project.hunk.view.ChunkDataHunkHolder;
import art.arcane.volmlib.util.io.ReactiveFolder;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.Looper;
import io.papermc.lib.PaperLib;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@EqualsAndHashCode(callSuper = true)
@Data
public class BukkitChunkGenerator extends ChunkGenerator implements PlatformChunkGenerator, Listener {
    private static final int LOAD_LOCKS = Runtime.getRuntime().availableProcessors() * 4;
    private static final long HOTLOAD_LOOP_DELAY_MS = 250L;
    private static final long HOTLOAD_MAINTENANCE_DELAY_MS = 4000L;
    private final Semaphore loadLock;
    private final IrisWorld world;
    private final File dataLocation;
    private final String dimensionKey;
    private final ReactiveFolder folder;
    private final ReentrantLock lock = new ReentrantLock();
    private final KList<BlockPopulator> populators;
    private final ChronoLatch hotloadChecker;
    private final AtomicBoolean setup;
    private final boolean studio;
    private final AtomicInteger a = new AtomicInteger(0);
    private volatile long lastChunkGenTime = 0L;
    private final CompletableFuture<Integer> spawnChunks = new CompletableFuture<>();
    private final AtomicCache<EngineTarget> targetCache = new AtomicCache<>();
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();
    private volatile Engine engine;
    private volatile Looper hotloader;
    private volatile StudioMode lastMode;
    private volatile DummyBiomeProvider dummyBiomeProvider;
    private volatile boolean closing;
    @Setter
    private volatile StudioGenerator studioGenerator;

    public BukkitChunkGenerator(IrisWorld world, boolean studio, File dataLocation, String dimensionKey) {
        setup = new AtomicBoolean(false);
        studioGenerator = null;
        dummyBiomeProvider = new DummyBiomeProvider();
        populators = new KList<>();
        loadLock = new Semaphore(LOAD_LOCKS);
        this.world = world;
        this.hotloadChecker = new ChronoLatch(1000, false);
        this.studio = studio;
        this.dataLocation = dataLocation;
        this.dimensionKey = dimensionKey;
        this.folder = new ReactiveFolder(
                dataLocation,
                (_a, _b, _c) -> hotload(),
                new KList<>(".iob", ".json"),
                new KList<>(".iris"),
                new KList<>()
        );
        this.closing = false;
        Bukkit.getServer().getPluginManager().registerEvents(this, Iris.instance);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        if (!world.name().equals(event.getWorld().getName())) return;
        Iris.instance.unregisterListener(this);
        world.setRawWorldSeed(event.getWorld().getSeed());
        if (initialize(event.getWorld())) return;

        Iris.warn("Failed to get Engine for " + event.getWorld().getName() + " re-trying...");
        J.s(() -> {
            if (!initialize(event.getWorld())) {
                Iris.error("Failed to get Engine for " + event.getWorld().getName() + "!");
            }
        }, 10);
    }

    private boolean initialize(World world) {
        Engine engine = getEngine(world);
        if (engine == null) return false;
        try {
            INMS.get().inject(world.getSeed(), engine, world);
            Iris.debug("Injected Iris Biome Source into " + world.getName());
            if (!studio) {
                J.s(() -> updateSpawnLocation(world), 1);
            }
        } catch (Throwable e) {
            Iris.reportError(e);
            Iris.error("Failed to inject biome source into " + world.getName());
            e.printStackTrace();
        }
        spawnChunks.complete(INMS.get().getSpawnChunkCount(world));
        Iris.instance.unregisterListener(this);
        IrisWorlds.get().put(world.getName(), dimensionKey);
        return true;
    }

    @Nullable
    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return getInitialSpawnLocation(world);
    }

    @Override
    public Location getInitialSpawnLocation(World world) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int y = Math.max(minY, Math.min(maxY, 96));
        return new Location(world, 0.5D, y, 0.5D);
    }

    private void updateSpawnLocation(World world) {
        Location initialSpawn = getInitialSpawnLocation(world);
        int chunkX = initialSpawn.getBlockX() >> 4;
        int chunkZ = initialSpawn.getBlockZ() >> 4;
        CompletableFuture<Chunk> chunkFuture = requestChunkAsync(world, chunkX, chunkZ, true);
        if (chunkFuture == null) {
            return;
        }

        chunkFuture.thenAccept(chunk ->
                J.runRegion(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> applySpawnLocation(chunk.getWorld(), initialSpawn)));
    }

    private void applySpawnLocation(World world, Location initialSpawn) {
        Location currentSpawn = world.getSpawnLocation();
        if (currentSpawn == null) {
            return;
        }

        if (!studio && (currentSpawn.getBlockX() != initialSpawn.getBlockX() || currentSpawn.getBlockZ() != initialSpawn.getBlockZ())) {
            return;
        }

        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int y = Math.max(minY, Math.min(maxY, world.getHighestBlockYAt(initialSpawn)));
        world.setSpawnLocation(new Location(world, initialSpawn.getX(), y, initialSpawn.getZ(), initialSpawn.getYaw(), initialSpawn.getPitch()));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Chunk> requestChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        try {
            Object result = World.class
                    .getMethod("getChunkAtAsync", int.class, int.class, boolean.class)
                    .invoke(world, chunkX, chunkZ, generate);
            if (result instanceof CompletableFuture<?>) {
                return (CompletableFuture<Chunk>) result;
            }
            if (PaperLib.isPaper()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Paper World#getChunkAtAsync returned a non-future result."));
            }
        } catch (Throwable e) {
            if (PaperLib.isPaper()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Paper World#getChunkAtAsync is unavailable.", e));
            }
        }

        return PaperLib.getChunkAtAsync(world, chunkX, chunkZ, generate);
    }

    private void setupEngine() {
        lastMode = StudioMode.NORMAL;
        engine = new IrisEngine(getTarget(), studio);
        populators.clear();
        targetCache.reset();
    }

    @NotNull
    @Override
    public EngineTarget getTarget() {
        if (engine != null) return engine.getTarget();

        return targetCache.aquire(() -> {
            IrisData data = IrisData.get(dataLocation);
            data.dump();
            data.clearLists();
            IrisDimension dimension = data.getDimensionLoader().load(dimensionKey);

            if (dimension == null) {
                Iris.error("Oh No! There's no pack in " + data.getDataFolder().getPath() + " or... there's no dimension for the key " + dimensionKey);
                IrisDimension test = IrisData.loadAnyDimension(dimensionKey, null);

                if (test != null) {
                    Iris.warn("Looks like " + dimensionKey + " exists in " + test.getLoadFile().getPath() + " ");
                    test = Iris.service(StudioSVC.class).installInto(Iris.getSender(), dimensionKey, dataLocation);
                    Iris.warn("Attempted to install into " + data.getDataFolder().getPath());

                    if (test != null) {
                        Iris.success("Woo! Patched the Engine!");
                        dimension = test;
                    } else {
                        Iris.error("Failed to patch dimension!");
                        throw new RuntimeException("Missing Dimension: " + dimensionKey);
                    }
                } else {
                    Iris.error("Nope, you don't have an installation containing " + dimensionKey + " try downloading it?");
                    throw new RuntimeException("Missing Dimension: " + dimensionKey);
                }
            }

            return new EngineTarget(world, dimension, data);
        });
    }

    @Override
    public void injectChunkReplacement(
            World world,
            int x,
            int z,
            Executor syncExecutor,
            ChunkReplacementOptions options,
            ChunkReplacementListener listener
    ) {
        boolean acquired = false;
        ChunkReplacementOptions effectiveOptions = Objects.requireNonNull(options, "options");
        ChunkReplacementListener effectiveListener = Objects.requireNonNull(listener, "listener");
        AtomicReference<String> phaseRef = new AtomicReference<>("start");
        try {
            setChunkReplacementPhase(phaseRef, effectiveListener, "acquire-load-lock", x, z);
            long acquireStart = System.currentTimeMillis();
            while (!loadLock.tryAcquire(5, TimeUnit.SECONDS)) {
                Iris.warn("Chunk replacement waiting for load lock at " + x + "," + z
                        + " for " + (System.currentTimeMillis() - acquireStart) + "ms.");
                effectiveListener.onPhase(phaseRef.get(), x, z, System.currentTimeMillis());
            }
            acquired = true;
            long acquireWait = System.currentTimeMillis() - acquireStart;
            if (acquireWait >= 5000L) {
                Iris.warn("Chunk replacement waited " + acquireWait + "ms for load lock at " + x + "," + z + ".");
            }
            TerrainChunk tc = TerrainChunk.create(world);
            this.world.bind(world);

            if (effectiveOptions.isFullMode()) {
                setChunkReplacementPhase(phaseRef, effectiveListener, "reset-mantle", x, z);
                resetMantleChunkForFullRegen(x, z);
            }

            setChunkReplacementPhase(phaseRef, effectiveListener, "generate", x, z);
            long generateStart = System.currentTimeMillis();
            boolean useMulticore = false;
            AtomicBoolean generateDone = new AtomicBoolean(false);
            AtomicLong generationWatchdogStart = new AtomicLong(System.currentTimeMillis());
            Thread generateThread = Thread.currentThread();
            J.a(() -> {
                while (!generateDone.get()) {
                    if (!J.sleep(5000)) {
                        return;
                    }
                    if (generateDone.get()) {
                        return;
                    }

                    Iris.warn("Chunk replacement still generating at " + x + "," + z
                            + " for " + (System.currentTimeMillis() - generationWatchdogStart.get()) + "ms"
                            + " thread=" + generateThread.getName()
                            + " state=" + generateThread.getState());
                    effectiveListener.onPhase(phaseRef.get(), x, z, System.currentTimeMillis());
                }
            });
            try {
                getEngine().generate(x << 4, z << 4, tc, useMulticore);
            } finally {
                generateDone.set(true);
            }
            long generateTook = System.currentTimeMillis() - generateStart;
            if (generateTook >= 5000L) {
                Iris.warn("Chunk replacement terrain generation took " + generateTook + "ms at " + x + "," + z + ".");
            }

            if (J.isFolia()) {
                setChunkReplacementPhase(phaseRef, effectiveListener, "folia-run-region", x, z);
                CountDownLatch latch = new CountDownLatch(1);
                Throwable[] failure = new Throwable[1];
                long regionScheduleStart = System.currentTimeMillis();
                if (!J.runRegion(world, x, z, () -> {
                    try {
                        setChunkReplacementPhase(phaseRef, effectiveListener, "apply-terrain", x, z);
                        phaseUnsafeSet("folia-region-run", x, z);
                        Chunk c = world.getChunkAt(x, z);
                        Iris.tickets.addTicket(c);
                        try {
                            for (Entity ee : c.getEntities()) {
                                if (ee instanceof Player) {
                                    continue;
                                }

                                ee.remove();
                            }

                            for (int i = getEngine().getHeight() >> 4; i >= 0; i--) {
                                int finalI = i << 4;
                                for (int xx = 0; xx < 16; xx++) {
                                    for (int yy = 0; yy < 16; yy++) {
                                        for (int zz = 0; zz < 16; zz++) {
                                            if (yy + finalI >= engine.getHeight() || yy + finalI < 0) {
                                                continue;
                                            }
                                            int y = yy + finalI + world.getMinHeight();
                                            c.getBlock(xx, y, zz).setBlockData(tc.getBlockData(xx, y, zz), false);
                                        }
                                    }
                                }
                            }

                            if (effectiveOptions.isFullMode()) {
                                setChunkReplacementPhase(phaseRef, effectiveListener, "overlay", x, z);
                                OverlayMetrics overlayMetrics = applyMantleOverlay(c, world, x, z);
                                effectiveListener.onOverlay(x, z, overlayMetrics.appliedBlocks(), overlayMetrics.objectKeys(), System.currentTimeMillis());
                            }

                            setChunkReplacementPhase(phaseRef, effectiveListener, "chunk-load-callback", x, z);
                            engine.getWorldManager().onChunkLoad(c, true);
                            world.refreshChunk(c.getX(), c.getZ());
                        } finally {
                            Iris.tickets.removeTicket(c);
                        }
                    } catch (Throwable e) {
                        failure[0] = e;
                    } finally {
                        latch.countDown();
                    }
                })) {
                    throw new IllegalStateException("Failed to schedule region task for chunk replacement at " + x + "," + z);
                }
                long regionScheduleTook = System.currentTimeMillis() - regionScheduleStart;
                if (regionScheduleTook >= 1000L) {
                    Iris.verbose("Chunk replacement region task scheduling took " + regionScheduleTook + "ms at " + x + "," + z + ".");
                }

                long regionWaitStart = System.currentTimeMillis();
                while (!latch.await(5, TimeUnit.SECONDS)) {
                    Iris.warn("Chunk replacement waiting on region task at " + x + "," + z
                            + " for " + (System.currentTimeMillis() - regionWaitStart) + "ms.");
                    effectiveListener.onPhase(phaseRef.get(), x, z, System.currentTimeMillis());
                }
                long regionWaitTook = System.currentTimeMillis() - regionWaitStart;
                if (regionWaitTook >= 5000L) {
                    Iris.warn("Chunk replacement region task completed after " + regionWaitTook + "ms at " + x + "," + z + ".");
                }
                if (failure[0] != null) {
                    effectiveListener.onFailurePhase(phaseRef.get(), x, z, failure[0], System.currentTimeMillis());
                    throw failure[0];
                }
            } else {
                setChunkReplacementPhase(phaseRef, effectiveListener, "paperlib-async-load", x, z);
                long loadChunkStart = System.currentTimeMillis();
                Chunk c = PaperLib.getChunkAtAsync(world, x, z).get();
                long loadChunkTook = System.currentTimeMillis() - loadChunkStart;
                if (loadChunkTook >= 5000L) {
                    Iris.warn("Chunk replacement chunk load took " + loadChunkTook + "ms at " + x + "," + z + ".");
                }

                setChunkReplacementPhase(phaseRef, effectiveListener, "apply-terrain", x, z);
                Iris.tickets.addTicket(c);
                try {
                    CompletableFuture.runAsync(() -> {
                        for (Entity ee : c.getEntities()) {
                            if (ee instanceof Player) {
                                continue;
                            }

                            ee.remove();
                        }
                    }, syncExecutor).get();

                    KList<CompletableFuture<?>> futures = new KList<>(1 + getEngine().getHeight() >> 4);
                    for (int i = getEngine().getHeight() >> 4; i >= 0; i--) {
                        int finalI = i << 4;
                        futures.add(CompletableFuture.runAsync(() -> {
                            for (int xx = 0; xx < 16; xx++) {
                                for (int yy = 0; yy < 16; yy++) {
                                    for (int zz = 0; zz < 16; zz++) {
                                        if (yy + finalI >= engine.getHeight() || yy + finalI < 0) {
                                            continue;
                                        }
                                        int y = yy + finalI + world.getMinHeight();
                                        c.getBlock(xx, y, zz).setBlockData(tc.getBlockData(xx, y, zz), false);
                                    }
                                }
                            }
                        }, syncExecutor));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                    if (effectiveOptions.isFullMode()) {
                        CompletableFuture.runAsync(() -> {
                            setChunkReplacementPhase(phaseRef, effectiveListener, "overlay", x, z);
                            OverlayMetrics overlayMetrics = applyMantleOverlay(c, world, x, z);
                            effectiveListener.onOverlay(x, z, overlayMetrics.appliedBlocks(), overlayMetrics.objectKeys(), System.currentTimeMillis());
                        }, syncExecutor).get();
                    }
                    CompletableFuture.runAsync(() -> {
                        setChunkReplacementPhase(phaseRef, effectiveListener, "chunk-load-callback", x, z);
                        engine.getWorldManager().onChunkLoad(c, true);
                        world.refreshChunk(c.getX(), c.getZ());
                    }, syncExecutor).get();
                } finally {
                    Iris.tickets.removeTicket(c);
                }
            }

            Iris.debug("Regenerated " + x + " " + z);
        } catch (Throwable e) {
            effectiveListener.onFailurePhase(phaseRef.get(), x, z, e, System.currentTimeMillis());
            Iris.error("======================================");
            Iris.error("Chunk replacement failed at phase=" + phaseRef.get() + " chunk=" + x + "," + z);
            e.printStackTrace();
            Iris.reportErrorChunk(x, z, e, "CHUNK");
            Iris.error("======================================");
            throw new IllegalStateException("Chunk replacement failed at phase=" + phaseRef.get() + " chunk=" + x + "," + z, e);
        } finally {
            if (acquired) {
                loadLock.release();
            }
        }
    }

    private static void phaseUnsafeSet(String phase, int x, int z) {
        Iris.verbose("Chunk replacement phase=" + phase + " chunk=" + x + "," + z);
    }

    private static void setChunkReplacementPhase(
            AtomicReference<String> phaseRef,
            ChunkReplacementListener listener,
            String phase,
            int x,
            int z
    ) {
        phaseRef.set(phase);
        listener.onPhase(phase, x, z, System.currentTimeMillis());
    }

    private void resetMantleChunkForFullRegen(int chunkX, int chunkZ) {
        MantleChunk<Matter> mantleChunk = getEngine().getMantle().getMantle().getChunk(chunkX, chunkZ).use();
        try {
            mantleChunk.deleteSlices(BlockData.class);
            mantleChunk.deleteSlices(String.class);
            mantleChunk.deleteSlices(TileWrapper.class);
            mantleChunk.flag(MantleFlag.PLANNED, false);
            mantleChunk.flag(MantleFlag.OBJECT, false);
            mantleChunk.flag(MantleFlag.REAL, false);
        } finally {
            mantleChunk.release();
        }
    }

    private OverlayMetrics applyMantleOverlay(Chunk chunk, World world, int chunkX, int chunkZ) {
        int minWorldY = world.getMinHeight();
        int maxWorldY = world.getMaxHeight();
        AtomicInteger appliedBlocks = new AtomicInteger();
        AtomicInteger objectKeys = new AtomicInteger();
        MantleChunk<Matter> mantleChunk = getEngine().getMantle().getMantle().getChunk(chunkX, chunkZ).use();
        try {
            mantleChunk.iterate(String.class, (x, y, z, value) -> {
                if (value != null && !value.isEmpty() && value.indexOf('@') > 0) {
                    objectKeys.incrementAndGet();
                }
            });
            mantleChunk.iterate(BlockData.class, (x, y, z, blockData) -> {
                if (!shouldApplyMantleOverlayBlock(blockData)) {
                    return;
                }
                int worldY = y + minWorldY;
                if (worldY < minWorldY || worldY >= maxWorldY) {
                    return;
                }
                chunk.getBlock(x & 15, worldY, z & 15).setBlockData(blockData, false);
                appliedBlocks.incrementAndGet();
            });
        } finally {
            mantleChunk.release();
        }
        return new OverlayMetrics(appliedBlocks.get(), objectKeys.get());
    }

    static boolean shouldApplyMantleOverlayBlock(BlockData blockData) {
        if (blockData == null) {
            return false;
        }

        Material material = blockData.getMaterial();
        return material != null && material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR;
    }

    private record OverlayMetrics(int appliedBlocks, int objectKeys) {
    }

    private Engine getEngine(WorldInfo world) {
        if (setup.get()) {
            return getEngine();
        }

        lock.lock();

        try {
            if (setup.get()) {
                return getEngine();
            }


            getWorld().setRawWorldSeed(world.getSeed());
            setupEngine();
            setup.set(true);
            this.hotloader = studio ? new Looper() {
                @Override
                protected long loop() {
                    if (shouldThrottleHotload()) {
                        return HOTLOAD_MAINTENANCE_DELAY_MS;
                    }

                    if (hotloadChecker.flip()) {
                        folder.check();
                    }

                    return HOTLOAD_LOOP_DELAY_MS;
                }
            } : null;

            if (studio) {
                hotloader.setPriority(Thread.MIN_PRIORITY);
                hotloader.start();
                hotloader.setName(getTarget().getWorld().name() + " Hotloader");
            }

            return engine;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        closeAsync();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> existing = closeFuture.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        closing = true;
        CompletableFuture<Void> future = withExclusiveControlFuture(() -> {
            Looper activeHotloader = hotloader;
            hotloader = null;
            if (isStudio() && activeHotloader != null) {
                activeHotloader.interrupt();
                try {
                    activeHotloader.join(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Iris.reportError(e);
                }
            }

            Engine currentEngine = engine;
            if (currentEngine != null && !currentEngine.isClosed()) {
                currentEngine.close();
            }
            folder.clear();
            populators.clear();
        });
        if (!closeFuture.compareAndSet(existing, future)) {
            CompletableFuture<Void> winningFuture = closeFuture.get();
            return winningFuture == null ? future : winningFuture;
        }

        future.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                closeFuture.compareAndSet(future, null);
            }
        });
        return future;
    }

    @Override
    public boolean isStudio() {
        return studio;
    }

    @Override
    public void hotload() {
        if (!isStudio() || closing) {
            return;
        }

        withExclusiveControl(() -> getEngine().hotload());
    }

    public void withExclusiveControl(Runnable r) {
        J.a(() -> {
            try {
                loadLock.acquire(LOAD_LOCKS);
                r.run();
                loadLock.release(LOAD_LOCKS);
            } catch (Throwable e) {
                Iris.reportError(e);
            }
        });
    }

    public CompletableFuture<Void> withExclusiveControlFuture(Runnable r) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        J.a(() -> {
            try {
                loadLock.acquire(LOAD_LOCKS);
                r.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            } finally {
                loadLock.release(LOAD_LOCKS);
            }
        });
        return future;
    }

    @Override
    public void touch(World world) {
        getEngine(world);
    }

    @Override
    public void generateNoise(@NotNull WorldInfo world, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData d) {
        if (closing) {
            return;
        }

        try {
            Engine engine = getEngine(world);
            lastChunkGenTime = System.currentTimeMillis();
            computeStudioGenerator();
            TerrainChunk tc = TerrainChunk.create(d);
            this.world.bind(world);
            if (studioGenerator != null) {
                studioGenerator.generateChunk(engine, tc, x, z);
            } else {
                ChunkDataHunkHolder blocks = new ChunkDataHunkHolder(d);
                Hunk<Biome> biomes = Hunk.viewBiomes(tc);
                boolean useMulticore = studio && !J.isFolia();
                engine.generate(x << 4, z << 4, blocks, biomes, useMulticore);
                blocks.apply();
            }

            Iris.debug("Generated " + x + " " + z);
        } catch (GenerationSessionException e) {
            if (closing || isExpectedTeardown(engine, e)) {
                return;
            }

            Iris.error("======================================");
            e.printStackTrace();
            Iris.reportErrorChunk(x, z, e, "CHUNK");
            Iris.error("======================================");

            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    d.setBlock(i, 0, j, Material.RED_GLAZED_TERRACOTTA.createBlockData());
                }
            }
        } catch (Throwable e) {
            Iris.error("======================================");
            e.printStackTrace();
            Iris.reportErrorChunk(x, z, e, "CHUNK");
            Iris.error("======================================");

            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    d.setBlock(i, 0, j, Material.RED_GLAZED_TERRACOTTA.createBlockData());
                }
            }
        }
    }

    private boolean isExpectedTeardown(Engine currentEngine, Throwable throwable) {
        if (throwable instanceof GenerationSessionException generationSessionException && generationSessionException.isExpectedTeardown()) {
            return true;
        }

        if (currentEngine != null && currentEngine.isClosing()) {
            return true;
        }

        return isMaintenanceActive();
    }

    private boolean isMaintenanceActive() {
        World realWorld = this.world.realWorld();
        return realWorld != null && IrisToolbelt.isWorldMaintenanceActive(realWorld);
    }

    private boolean shouldThrottleHotload() {
        if (isMaintenanceActive()) {
            return true;
        }

        if (System.currentTimeMillis() - lastChunkGenTime < 2000L) {
            return true;
        }

        World realWorld = this.world.realWorld();
        PregeneratorJob pregeneratorJob = PregeneratorJob.getInstance();
        return realWorld != null && pregeneratorJob != null && pregeneratorJob.targetsWorld(realWorld);
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        Engine currentEngine = engine;
        if (currentEngine == null || !setup.get()) {
            currentEngine = getEngine(worldInfo);
        }

        boolean ignoreFluid = switch (heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> true;
            default -> false;
        };

        return currentEngine.getMinHeight() + currentEngine.getHeight(x, z, ignoreFluid) + 1;
    }

    private void computeStudioGenerator() {
        StudioMode desired = getEngine().getDimension().getStudioMode();
        if (studio && art.arcane.iris.core.runtime.ObjectStudioActivation.isActive(getEngine().getDimension().getLoadKey())) {
            desired = StudioMode.OBJECT_BUFFET;
        }
        if (!desired.equals(lastMode)) {
            lastMode = desired;
            desired.inject(this);
        }
    }

    @NotNull
    @Override
    public List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return populators;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        try {
            Engine e = this.engine;
            if (e == null) {
                return true;
            }
            return e.getDimension().getImportedStructures().getMode() != VanillaStructureMode.ALL_OFF;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {

    }

    @Nullable
    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return dummyBiomeProvider;
    }
}
