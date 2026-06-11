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

package art.arcane.iris.core.runtime;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.format.Form;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkJobReporter {
    private static final int PROGRESS_BAR_WIDTH = 24;
    private static final int REPORT_INTERVAL_TICKS = 5;
    private static final int FINISH_LINGER_TICKS = 60;

    private final VolmitSender sender;
    private final String title;
    private final String worldName;

    private final AtomicReference<String> stage = new AtomicReference<>("Preparing");
    private final AtomicReference<Double> progress = new AtomicReference<>(0.0D);
    private final AtomicBoolean complete = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger applied = new AtomicInteger(0);
    private final AtomicInteger failures = new AtomicInteger(0);
    private volatile int total = 0;
    private volatile long startMs = 0L;

    public ChunkJobReporter(VolmitSender sender, String title, World world) {
        this.sender = sender;
        this.title = title;
        this.worldName = world.getName();
    }

    public static List<int[]> orderedTargets(int centerChunkX, int centerChunkZ, int radius) {
        List<int[]> targets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                targets.add(new int[]{centerChunkX + dx, centerChunkZ + dz});
            }
        }

        targets.sort(Comparator.comparingInt(t -> {
            int ox = t[0] - centerChunkX;
            int oz = t[1] - centerChunkZ;
            return ox * ox + oz * oz;
        }));
        return targets;
    }

    public void start() {
        startMs = System.currentTimeMillis();
        startReporter();
    }

    public void setStage(String value) {
        stage.set(value);
    }

    public void setTotal(int value) {
        total = value;
    }

    public void countApplied(boolean ok) {
        if (ok) {
            applied.incrementAndGet();
        } else {
            failures.incrementAndGet();
        }

        int finished = applied.get() + failures.get();
        if (total > 0) {
            progress.set(Math.min(0.999D, (double) finished / total));
        }
    }

    public void finish(boolean error) {
        progress.set(1.0D);
        failed.set(error || (applied.get() == 0 && total > 0));
        complete.set(true);
    }

    public int applied() {
        return applied.get();
    }

    public int failures() {
        return failures.get();
    }

    private void startReporter() {
        boolean player = sender.isPlayer() && sender.player() != null;
        BossBar bossBar = player
                ? Bukkit.createBossBar(C.GOLD + title + " " + C.AQUA + "WORKING", BarColor.BLUE, BarStyle.SEGMENTED_20)
                : null;
        if (bossBar != null) {
            bossBar.setProgress(0.0D);
            bossBar.addPlayer(sender.player());
            bossBar.setVisible(true);
        }

        AtomicInteger taskId = new AtomicInteger(-1);
        taskId.set(J.ar(() -> {
            double currentProgress = Math.max(0.0D, Math.min(1.0D, progress.get()));
            int percent = (int) Math.round(currentProgress * 100.0D);
            long elapsed = System.currentTimeMillis() - startMs;

            if (complete.get()) {
                J.car(taskId.get());
                finishReporter(bossBar, elapsed);
                return;
            }

            String label = stage.get() + " " + applied.get() + "/" + (total <= 0 ? "?" : total);
            if (bossBar != null) {
                bossBar.setProgress(Math.min(1.0D, currentProgress));
                bossBar.setTitle(C.GOLD + title + " " + C.AQUA + stage.get() + C.GRAY + " " + C.YELLOW + percent + "%");
            }
            if (sender.isPlayer()) {
                sender.sendAction(progressBar(currentProgress) + C.GRAY + " " + C.YELLOW + percent + "%"
                        + C.GRAY + " | " + C.WHITE + label);
            }
        }, REPORT_INTERVAL_TICKS));
    }

    private void finishReporter(BossBar bossBar, long elapsed) {
        boolean ok = !failed.get();
        String summary = applied.get() + "/" + total + " chunk(s) in " + Form.duration(elapsed, 1)
                + (failures.get() > 0 ? " (" + failures.get() + " failed)" : "");

        if (bossBar != null) {
            bossBar.setProgress(1.0D);
            bossBar.setColor(ok ? BarColor.GREEN : BarColor.RED);
            bossBar.setTitle(C.GOLD + title + " " + (ok ? C.GREEN + "DONE" : C.RED + "FAILED")
                    + C.GRAY + " " + C.YELLOW + summary);
            J.a(() -> {
                bossBar.removeAll();
                bossBar.setVisible(false);
            }, FINISH_LINGER_TICKS);
        }

        if (sender.isPlayer()) {
            sender.sendAction(progressBar(1.0D) + C.GRAY + " " + (ok ? C.GREEN + "Done" : C.RED + "Failed")
                    + C.GRAY + " | " + C.WHITE + summary);
        }
        sender.sendMessage((ok ? C.GREEN + title + " complete: " : C.RED + title + " finished with errors: ") + summary);
        IrisLogging.info(title + " done: world=" + worldName + " " + summary);
    }

    private String progressBar(double value) {
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, value)) * PROGRESS_BAR_WIDTH);
        StringBuilder bar = new StringBuilder(C.DARK_GRAY + "[");
        for (int i = 0; i < PROGRESS_BAR_WIDTH; i++) {
            bar.append(i < filled ? C.GREEN + "|" : C.DARK_GRAY + "|");
        }
        bar.append(C.DARK_GRAY).append("]");
        return bar.toString();
    }
}
