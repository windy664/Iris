/*
 * Iris is a World Generator for Minecraft Bukkit Servers
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

package art.arcane.iris.core.runtime;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public final class GoldenHashScanner {
    private static final AtomicInteger ACTIVE_SCANS = new AtomicInteger(0);
    private static final String FORMAT = "iris-goldenhash v1";

    public static boolean isScanActive() {
        return ACTIVE_SCANS.get() > 0;
    }
    private static final int BIOME_STEP = 4;
    private static final int MAX_REPORTED_MISMATCHES = 10;
    private static final byte[] NULL_BIOME = "null\n".getBytes(StandardCharsets.UTF_8);

    private final World world;
    private final Engine engine;
    private final VolmitSender sender;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radius;
    private final boolean resetMantle;
    private final int threads;
    private final boolean deep;
    private final File goldenFile;
    private final ChunkJobReporter reporter;

    public GoldenHashScanner(World world, Engine engine, VolmitSender sender, int centerChunkX, int centerChunkZ, int radius, boolean resetMantle, int threads, boolean deep) {
        this.world = world;
        this.engine = engine;
        this.sender = sender;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radius = Math.max(0, radius);
        this.resetMantle = resetMantle;
        this.threads = Math.max(1, threads);
        this.deep = deep;
        this.goldenFile = IrisPlatforms.get().dataFile("golden", engine.getDimension().getLoadKey()
                + "-s" + world.getSeed()
                + "-c" + centerChunkX + "x" + centerChunkZ
                + "-r" + this.radius + ".hashes");
        this.reporter = new ChunkJobReporter(sender, "GoldenHash", world);
    }

    public void start() {
        reporter.start();
        Thread thread = new Thread(this::run, "Iris GoldenHash");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        boolean error = false;
        ACTIVE_SCANS.incrementAndGet();
        try {
            if (resetMantle) {
                reporter.setStage("Resetting mantle");
                resetMantleFull();
            }

            List<int[]> targets = ChunkJobReporter.orderedTargets(centerChunkX, centerChunkZ, radius);
            reporter.setTotal(targets.size());
            reporter.setStage("Generating");
            Map<Long, String> lines = scan(targets);

            if (lines.size() != targets.size()) {
                sender.sendMessage(C.RED + "GoldenHash aborted: " + (targets.size() - lines.size()) + " chunk(s) failed to generate. No golden file written.");
                error = true;
                return;
            }

            reporter.setStage("Comparing");
            if (goldenFile.exists()) {
                error = !verify(lines);
            } else {
                capture(lines);
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            error = true;
        } finally {
            ACTIVE_SCANS.decrementAndGet();
            reporter.finish(error);
        }
    }

    private void resetMantleFull() {
        Mantle mantle = engine.getMantle().getMantle();
        mantle.saveAll();
        File folder = mantle.getDataFolder();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private Map<Long, String> scan(List<int[]> targets) throws InterruptedException {
        Map<Long, String> lines = new ConcurrentHashMap<>();
        Semaphore inFlight = new Semaphore(threads);
        CountDownLatch done = new CountDownLatch(targets.size());

        for (int[] target : targets) {
            int chunkX = target[0];
            int chunkZ = target[1];

            inFlight.acquire();
            MultiBurst.burst.lazy(() -> {
                boolean ok = false;
                try {
                    TerrainChunk buffer = TerrainChunk.create(world);
                    engine.generate(chunkX << 4, chunkZ << 4, buffer, false);
                    lines.put(chunkKey(chunkX, chunkZ), hashChunk(chunkX, chunkZ, buffer));
                    if (deep) {
                        writeDeepDump(chunkX, chunkZ, buffer);
                    }
                    ok = true;
                } catch (Throwable e) {
                    IrisLogging.reportError(e);
                } finally {
                    reporter.countApplied(ok);
                    inFlight.release();
                    done.countDown();
                }
            });
        }

        done.await();
        return lines;
    }

    private String hashChunk(int chunkX, int chunkZ, TerrainChunk buffer) {
        MessageDigest blockDigest = sha256();
        MessageDigest biomeDigest = sha256();
        int minY = buffer.getMinHeight();
        int maxY = buffer.getMaxHeight();
        IdentityHashMap<PlatformBlockState, byte[]> blockCache = new IdentityHashMap<>();
        Map<PlatformBiome, byte[]> biomeCache = new HashMap<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    PlatformBlockState data = buffer.getBlockData(x, y, z);
                    byte[] bytes = blockCache.computeIfAbsent(data, (PlatformBlockState d) -> (d.key() + "\n").getBytes(StandardCharsets.UTF_8));
                    blockDigest.update(bytes);
                }
            }
        }

        for (int x = 0; x < 16; x += BIOME_STEP) {
            for (int z = 0; z < 16; z += BIOME_STEP) {
                for (int y = minY; y < maxY; y += BIOME_STEP) {
                    PlatformBiome biome = buffer.getBiome(x, y, z);
                    byte[] bytes = biome == null
                            ? NULL_BIOME
                            : biomeCache.computeIfAbsent(biome, (PlatformBiome b) -> (b.key() + "\n").getBytes(StandardCharsets.UTF_8));
                    biomeDigest.update(bytes);
                }
            }
        }

        return chunkX + " " + chunkZ + " "
                + HexFormat.of().formatHex(blockDigest.digest()) + " "
                + HexFormat.of().formatHex(biomeDigest.digest());
    }

    private void capture(Map<Long, String> lines) throws IOException {
        List<String> body = orderedBody(lines);
        String combined = combinedHash(body);
        List<String> out = new ArrayList<>();
        out.add("#" + FORMAT);
        out.add("#world=" + world.getName());
        out.add("#dim=" + engine.getDimension().getLoadKey());
        out.add("#seed=" + world.getSeed());
        out.add("#mc=" + Bukkit.getBukkitVersion());
        out.add("#minY=" + world.getMinHeight() + " maxY=" + world.getMaxHeight());
        out.add("#center=" + centerChunkX + "," + centerChunkZ);
        out.add("#radius=" + radius);
        out.addAll(body);
        out.add("#combined=" + combined);
        Files.write(goldenFile.toPath(), out, StandardCharsets.UTF_8);

        sender.sendMessage(C.GREEN + "Golden captured: " + C.GOLD + body.size() + " chunks" + C.GREEN + " combined=" + C.GOLD + shortHash(combined));
        sender.sendMessage(C.GRAY + goldenFile.getAbsolutePath());
        IrisLogging.info("goldenhash captured: " + goldenFile.getAbsolutePath() + " combined=" + combined);
    }

    private boolean verify(Map<Long, String> lines) throws IOException {
        List<String> existing = Files.readAllLines(goldenFile.toPath(), StandardCharsets.UTF_8);
        Map<String, String> meta = new HashMap<>();
        Map<String, String> goldenChunks = new HashMap<>();
        for (String line : existing) {
            if (line.startsWith("#")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    meta.put(line.substring(1, eq), line.substring(eq + 1));
                }
            } else if (!line.isBlank()) {
                int second = line.indexOf(' ', line.indexOf(' ') + 1);
                goldenChunks.put(line.substring(0, second), line);
            }
        }

        String expectedSeed = String.valueOf(world.getSeed());
        String expectedDim = engine.getDimension().getLoadKey();
        if (!expectedSeed.equals(meta.get("seed")) || !expectedDim.equals(meta.get("dim"))) {
            sender.sendMessage(C.RED + "Golden file is for dim=" + meta.get("dim") + " seed=" + meta.get("seed")
                    + " but this world is dim=" + expectedDim + " seed=" + expectedSeed + ". Aborting.");
            return false;
        }
        String mc = Bukkit.getBukkitVersion();
        if (!mc.equals(meta.get("mc"))) {
            sender.sendMessage(C.YELLOW + "Golden was captured on mc=" + meta.get("mc") + ", running mc=" + mc + ". Diffs may be version-induced.");
        }

        List<String> body = orderedBody(lines);
        List<String> mismatches = new ArrayList<>();
        for (String line : body) {
            int second = line.indexOf(' ', line.indexOf(' ') + 1);
            String key = line.substring(0, second);
            String golden = goldenChunks.get(key);
            if (!line.equals(golden)) {
                mismatches.add(key + (golden == null ? " (missing in golden)" : ""));
            }
        }

        String combined = combinedHash(body);
        if (mismatches.isEmpty()) {
            sender.sendMessage(C.GREEN + "GOLDEN MATCH: " + C.GOLD + body.size() + "/" + goldenChunks.size() + C.GREEN
                    + " chunks, combined=" + C.GOLD + shortHash(combined));
            IrisLogging.info("goldenhash MATCH: " + goldenFile.getName() + " combined=" + combined);
            return true;
        }

        sender.sendMessage(C.RED + "GOLDEN MISMATCH: " + mismatches.size() + "/" + body.size() + " chunks differ.");
        for (int i = 0; i < Math.min(MAX_REPORTED_MISMATCHES, mismatches.size()); i++) {
            sender.sendMessage(C.RED + "  chunk " + mismatches.get(i));
        }
        if (mismatches.size() > MAX_REPORTED_MISMATCHES) {
            sender.sendMessage(C.RED + "  ... and " + (mismatches.size() - MAX_REPORTED_MISMATCHES) + " more");
        }

        File current = new File(goldenFile.getParentFile(), goldenFile.getName() + ".new");
        List<String> out = new ArrayList<>(body);
        out.add("#combined=" + combined);
        Files.write(current.toPath(), out, StandardCharsets.UTF_8);
        sender.sendMessage(C.YELLOW + "Current hashes written to " + current.getName());
        IrisLogging.info("goldenhash MISMATCH: " + mismatches.size() + "/" + body.size() + " -> " + current.getAbsolutePath());

        reporter.setStage("Diagnosing");
        diagnose(mismatches.getFirst());
        return false;
    }

    private void diagnose(String mismatchKey) {
        try {
            String[] parts = mismatchKey.trim().split(" ");
            int chunkX = Integer.parseInt(parts[0]);
            int chunkZ = Integer.parseInt(parts[1]);

            TerrainChunk first = TerrainChunk.create(world);
            engine.generate(chunkX << 4, chunkZ << 4, first, false);
            TerrainChunk second = TerrainChunk.create(world);
            engine.generate(chunkX << 4, chunkZ << 4, second, false);

            int minY = first.getMinHeight();
            int maxY = first.getMaxHeight();
            List<String> diffs = new ArrayList<>();
            for (int x = 0; x < 16 && diffs.size() < 50; x++) {
                for (int z = 0; z < 16 && diffs.size() < 50; z++) {
                    for (int y = minY; y < maxY && diffs.size() < 50; y++) {
                        String a = first.getBlockData(x, y, z).key();
                        String b = second.getBlockData(x, y, z).key();
                        if (!a.equals(b)) {
                            diffs.add(x + " " + y + " " + z + " | " + a + " | " + b);
                        }
                    }
                }
            }

            EngineMantle engineMantle = engine.getMantle();
            int margin = Math.max(engineMantle.getRadius(), engineMantle.getRealRadius()) + 1;
            for (int dx = -margin; dx <= margin; dx++) {
                for (int dz = -margin; dz <= margin; dz++) {
                    engineMantle.getMantle().deleteChunk(chunkX + dx, chunkZ + dz);
                }
            }
            TerrainChunk reset = TerrainChunk.create(world);
            engine.generate(chunkX << 4, chunkZ << 4, reset, false);
            List<String> mantleDiffs = new ArrayList<>();
            for (int x = 0; x < 16 && mantleDiffs.size() < 80; x++) {
                for (int z = 0; z < 16 && mantleDiffs.size() < 80; z++) {
                    for (int y = minY; y < maxY && mantleDiffs.size() < 80; y++) {
                        String a = first.getBlockData(x, y, z).key();
                        String b = reset.getBlockData(x, y, z).key();
                        if (!a.equals(b)) {
                            mantleDiffs.add(x + " " + y + " " + z + " | scan: " + a + " | mantle-reset: " + b);
                        }
                    }
                }
            }

            List<String> report = new ArrayList<>();
            report.add("#goldenhash diagnosis chunk=" + chunkX + "," + chunkZ);
            report.add("#repeat-generation: " + (diffs.isEmpty() ? "STABLE (nondeterminism is order/state-dependent, not per-call)" : "UNSTABLE (" + diffs.size() + "+ diffs between two back-to-back generations)"));
            report.addAll(diffs);
            report.add("#mantle-reset regeneration: " + (mantleDiffs.isEmpty() ? "STABLE (mantle rebuild reproduces scan output)" : "DIVERGED (" + mantleDiffs.size() + "+ diffs - mantle build is state/order dependent)"));
            report.addAll(mantleDiffs);
            report.add("#full non-air dump of generation 1 follows (x y z state)");
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        String state = first.getBlockData(x, y, z).key();
                        if (!state.equals("minecraft:air") && !state.equals("minecraft:cave_air") && !state.equals("minecraft:void_air")) {
                            report.add(x + " " + y + " " + z + " " + state);
                        }
                    }
                }
            }

            File diag = new File(goldenFile.getParentFile(), goldenFile.getName() + ".diag-c" + chunkX + "x" + chunkZ + ".txt");
            Files.write(diag.toPath(), report, StandardCharsets.UTF_8);
            sender.sendMessage((diffs.isEmpty() ? C.YELLOW + "Repeat-gen STABLE" : C.RED + "Repeat-gen UNSTABLE (" + diffs.size() + "+ block diffs)")
                    + C.GRAY + ", " + (mantleDiffs.isEmpty() ? C.YELLOW + "mantle-reset STABLE" : C.RED + "mantle-reset DIVERGED (" + mantleDiffs.size() + "+ diffs)")
                    + C.GRAY + " -> " + diag.getName());
            IrisLogging.info("goldenhash diag: chunk=" + chunkX + "," + chunkZ + " repeatStable=" + diffs.isEmpty() + " -> " + diag.getAbsolutePath());
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            sender.sendMessage(C.RED + "Diagnosis failed: " + e.getMessage());
        }
    }

    private List<String> orderedBody(Map<Long, String> lines) {
        Map<Long, String> sorted = new TreeMap<>(lines);
        return new ArrayList<>(sorted.values());
    }

    private String combinedHash(List<String> body) {
        MessageDigest digest = sha256();
        for (String line : body) {
            digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void writeDeepDump(int chunkX, int chunkZ, TerrainChunk buffer) throws IOException {
        File dir = new File(goldenFile.getParentFile(), goldenFile.getName() + (goldenFile.exists() ? ".deep-verify" : ".deep"));
        dir.mkdirs();
        int minY = buffer.getMinHeight();
        int maxY = buffer.getMaxHeight();
        List<String> out = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    String state = buffer.getBlockData(x, y, z).key();
                    if (!state.equals("minecraft:air") && !state.equals("minecraft:cave_air") && !state.equals("minecraft:void_air")) {
                        out.add(x + " " + y + " " + z + " " + state);
                    }
                }
            }
        }
        Files.write(new File(dir, chunkX + "_" + chunkZ + ".txt").toPath(), out, StandardCharsets.UTF_8);
    }

    private static String shortHash(String hex) {
        return hex.substring(0, 12);
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
