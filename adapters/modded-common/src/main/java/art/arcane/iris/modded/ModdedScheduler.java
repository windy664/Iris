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

package art.arcane.iris.modded;

import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.spi.PlatformWorld;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModdedScheduler implements PlatformScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final int ASYNC_CORE_THREADS = 2;
    private static final int ASYNC_MAX_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int ASYNC_QUEUE_CAPACITY = 4096;
    private static final long ASYNC_KEEP_ALIVE_SECONDS = 30L;

    private static volatile Thread mainThread;

    private final ThreadPoolExecutor asyncExecutor;
    private final ConcurrentLinkedQueue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DelayedTask> delayedQueue = new ConcurrentLinkedQueue<>();

    public ModdedScheduler() {
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(ASYNC_QUEUE_CAPACITY);
        this.asyncExecutor = new ThreadPoolExecutor(
            ASYNC_CORE_THREADS,
            ASYNC_MAX_THREADS,
            ASYNC_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            workQueue,
            new AsyncThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy());
        this.asyncExecutor.allowCoreThreadTimeOut(true);
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        mainThread = server.getRunningThread();
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            return;
        }
        scheduler.drain();
    }

    @Override
    public void global(Runnable task) {
        if (task == null) {
            return;
        }
        if (onMainThread()) {
            runGuarded(task);
            return;
        }
        mainQueue.add(task);
    }

    @Override
    public void region(PlatformWorld world, int chunkX, int chunkZ, Runnable task) {
        global(task);
    }

    @Override
    public void async(Runnable task) {
        if (task == null) {
            return;
        }
        asyncExecutor.execute(() -> runGuarded(task));
    }

    @Override
    public void laterGlobal(Runnable task, int ticks) {
        if (task == null) {
            return;
        }
        if (ticks <= 0) {
            global(task);
            return;
        }
        delayedQueue.add(new DelayedTask(task, ticks));
    }

    @Override
    public void laterRegion(PlatformWorld world, int chunkX, int chunkZ, Runnable task, int ticks) {
        laterGlobal(task, ticks);
    }

    public void shutdown() {
        asyncExecutor.shutdownNow();
        mainQueue.clear();
        delayedQueue.clear();
    }

    private void drain() {
        promoteDelayed();
        Runnable task;
        while ((task = mainQueue.poll()) != null) {
            runGuarded(task);
        }
    }

    private void promoteDelayed() {
        if (delayedQueue.isEmpty()) {
            return;
        }
        List<DelayedTask> retained = new ArrayList<>();
        DelayedTask delayed;
        while ((delayed = delayedQueue.poll()) != null) {
            if (delayed.tick()) {
                mainQueue.add(delayed.task());
            } else {
                retained.add(delayed);
            }
        }
        delayedQueue.addAll(retained);
    }

    private boolean onMainThread() {
        Thread main = mainThread;
        return main != null && Thread.currentThread() == main;
    }

    private void runGuarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable error) {
            LOGGER.error("Iris scheduled task failed", error);
        }
    }

    private static final class DelayedTask {
        private final Runnable task;
        private int remaining;

        private DelayedTask(Runnable task, int remaining) {
            this.task = task;
            this.remaining = remaining;
        }

        private boolean tick() {
            remaining--;
            return remaining <= 0;
        }

        private Runnable task() {
            return task;
        }
    }

    private static final class AsyncThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "iris-modded-async-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
