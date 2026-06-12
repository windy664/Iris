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

package art.arcane.iris.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricWorldCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private FabricWorldCheck() {
    }

    public static void schedule() {
        Thread thread = new Thread(FabricWorldCheck::waitAndRun, "Iris World Check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void waitAndRun() {
        long start = System.currentTimeMillis();
        MinecraftServer server = null;
        while (System.currentTimeMillis() - start < 600000L) {
            Object instance = FabricLoader.getInstance().getGameInstance();
            if (instance instanceof MinecraftServer candidate && candidate.isReady()) {
                server = candidate;
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (server == null) {
            LOGGER.error("[worldcheck] server did not become ready within 10 minutes");
            return;
        }

        MinecraftServer serverRef = server;
        AtomicBoolean pass = new AtomicBoolean(false);
        try {
            serverRef.submit(() -> pass.set(run(serverRef))).join();
        } catch (Throwable e) {
            LOGGER.error("[worldcheck] check failed", e);
        }

        LOGGER.info("[worldcheck] shutting down dev server (result={})", pass.get() ? "PASS" : "FAIL");
        serverRef.halt(false);
    }

    private static boolean run(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        String generatorClass = overworld.getChunkSource().getGenerator().getClass().getName();
        LOGGER.info("[worldcheck] overworld generator: {}", generatorClass);
        boolean irisGenerator = overworld.getChunkSource().getGenerator() instanceof IrisFabricChunkGenerator;
        if (!irisGenerator) {
            LOGGER.error("[worldcheck] overworld is NOT using IrisFabricChunkGenerator");
        }

        BlockPos spawn = overworld.getRespawnData().pos();
        LOGGER.info("[worldcheck] spawn: {} {} {} (minY={} height={})", spawn.getX(), spawn.getY(), spawn.getZ(), overworld.getMinY(), overworld.getHeight());

        MessageDigest digest = sha256();
        List<String> samples = new ArrayList<>();
        Set<String> surfaceKeys = new LinkedHashSet<>();
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int x = spawn.getX() + (dx - 2) * 16 + 8;
                int z = spawn.getZ() + (dz - 2) * 16 + 8;
                overworld.getChunk(x >> 4, z >> 4);
                int y = overworld.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState surface = overworld.getBlockState(new BlockPos(x, y - 1, z));
                String key = BuiltInRegistries.BLOCK.getKey(surface.getBlock()).toString();
                String line = x + " " + (y - 1) + " " + z + " " + key;
                samples.add(line);
                surfaceKeys.add(key);
                digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }

        for (int i = 0; i < Math.min(6, samples.size()); i++) {
            LOGGER.info("[worldcheck] surface sample: {}", samples.get(i));
        }
        LOGGER.info("[worldcheck] surface digest: {} ({} columns, {} distinct surface blocks: {})",
                HexFormat.of().formatHex(digest.digest()).substring(0, 12), samples.size(), surfaceKeys.size(), surfaceKeys);

        ChunkAccess zeroChunk = overworld.getChunk(0, 0);
        int nonEmptySections = 0;
        for (LevelChunkSection section : zeroChunk.getSections()) {
            if (!section.hasOnlyAir()) {
                nonEmptySections++;
            }
        }
        Set<String> columnKeys = new LinkedHashSet<>();
        int surfaceY = overworld.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
        for (int y = overworld.getMinY(); y < surfaceY; y += 16) {
            BlockState state = zeroChunk.getBlockState(new BlockPos(8, y, 8));
            if (!state.isAir()) {
                columnKeys.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        }
        LOGGER.info("[worldcheck] chunk 0,0: {} non-empty sections of {}; column blocks at (8,*,8): {}",
                nonEmptySections, zeroChunk.getSections().length, columnKeys);

        boolean sectionsOk = nonEmptySections >= 4;
        boolean varietyOk = columnKeys.size() >= 2 || surfaceKeys.size() >= 2;
        if (!sectionsOk) {
            LOGGER.error("[worldcheck] chunk 0,0 looks empty/vanilla-flat ({} non-empty sections)", nonEmptySections);
        }
        if (!varietyOk) {
            LOGGER.error("[worldcheck] generated terrain has no block variety (flat-world signature)");
        }

        boolean pass = irisGenerator && sectionsOk && varietyOk;
        LOGGER.info("[worldcheck] {}", pass ? "PASS" : "FAIL");
        return pass;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
