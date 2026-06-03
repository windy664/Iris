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

package art.arcane.iris.core.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.pregenerator.PregenTask;
import art.arcane.iris.core.pregenerator.methods.RegenPregenMethod;
import art.arcane.iris.core.service.IrisEngineSVC;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisPackBenchmarking;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngineMantle;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.iris.util.common.director.specialhandlers.NullableDimensionHandler;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.CountingDataInputStream;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.iris.util.nbt.common.mca.MCAFile;
import art.arcane.iris.util.nbt.common.mca.MCAUtil;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

@Director(name = "Developer", origin = DirectorOrigin.BOTH, description = "Iris World Manager", aliases = {"dev"})
public class CommandDeveloper implements DirectorExecutor {
    @Director(description = "Get Loaded TectonicPlates Count", origin = DirectorOrigin.BOTH, sync = true)
    public void EngineStatus() {
        Iris.service(IrisEngineSVC.class)
                .engineStatus(sender());
    }

    @Director(description = "Send a test exception to sentry")
    public void Sentry() {
        Engine engine = engine();
        if (engine != null) IrisContext.getOr(engine);
        Iris.reportError(new Exception("This is a test"));
    }

    @Director(description = "Hash generated block output of a fixed area for determinism/identity testing", origin = DirectorOrigin.BOTH)
    public void genhash(
            @Param(description = "The world to hash", contextual = true)
            World world,
            @Param(description = "Radius in chunks around the center", defaultValue = "4")
            int radius,
            @Param(description = "Center chunk X", defaultValue = "0")
            int centerX,
            @Param(description = "Center chunk Z", defaultValue = "0")
            int centerZ) {
        if (world == null) {
            sender().sendMessage(C.RED + "World is null.");
            return;
        }

        VolmitSender sender = sender();
        sender.sendMessage(C.GREEN + "genhash started: " + ((radius * 2 + 1) * (radius * 2 + 1)) + " chunks...");
        J.a(() -> runGenhash(sender, world, radius, centerX, centerZ));
    }

    private void runGenhash(VolmitSender sender, World world, int radius, int centerX, int centerZ) {
        long startMs = M.ms();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        long globalHash = 0L;
        long solidBlocks = 0L;
        Map<String, Long> histogram = new TreeMap<>();
        JsonObject chunkHashes = new JsonObject();

        for (int rx = centerX - radius; rx <= centerX + radius; rx++) {
            for (int rz = centerZ - radius; rz <= centerZ + radius; rz++) {
                org.bukkit.ChunkSnapshot snapshot;
                try {
                    org.bukkit.Chunk loaded = io.papermc.lib.PaperLib.getChunkAtAsync(world, rx, rz, true).get();
                    snapshot = loaded.getChunkSnapshot(false, false, false);
                } catch (Throwable e) {
                    Iris.reportError(e);
                    sender.sendMessage(C.RED + "genhash failed at chunk " + rx + "," + rz + ": " + e.getMessage());
                    return;
                }
                long chunkHash = 0L;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int worldX = (rx << 4) + x;
                        int worldZ = (rz << 4) + z;
                        for (int y = minY; y < maxY; y++) {
                            org.bukkit.Material material = snapshot.getBlockType(x, y, z);
                            long positionSeed = ((long) worldX * 0x9E3779B97F4A7C15L)
                                    ^ ((long) y * 0xC2B2AE3D27D4EB4FL)
                                    ^ ((long) worldZ * 0x165667B19E3779F9L);
                            long blockHash = genHashMix(positionSeed ^ ((long) (material.ordinal() + 1) * 0xD6E8FEB86659FD93L));
                            chunkHash ^= blockHash;
                            if (material != org.bukkit.Material.AIR
                                    && material != org.bukkit.Material.CAVE_AIR
                                    && material != org.bukkit.Material.VOID_AIR) {
                                histogram.merge(material.name(), 1L, Long::sum);
                                solidBlocks++;
                            }
                        }
                    }
                }
                globalHash ^= chunkHash;
                chunkHashes.addProperty(rx + "," + rz, Long.toHexString(chunkHash));
            }
        }

        int side = radius * 2 + 1;
        JsonObject result = new JsonObject();
        result.addProperty("global", Long.toHexString(globalHash));
        result.addProperty("chunks", side * side);
        result.addProperty("solidBlocks", solidBlocks);
        result.addProperty("minY", minY);
        result.addProperty("maxY", maxY);
        JsonObject hist = new JsonObject();
        for (Map.Entry<String, Long> entry : histogram.entrySet()) {
            hist.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("histogram", hist);
        result.add("chunkHashes", chunkHashes);

        File out = new File(Iris.instance.getDataFolder(), "genhash.json");
        try {
            Files.writeString(out.toPath(), result.toString());
        } catch (IOException e) {
            Iris.reportError(e);
        }

        sender.sendMessage(C.GREEN + "genhash global=" + C.GOLD + Long.toHexString(globalHash)
                + C.GREEN + " chunks=" + (side * side) + " solid=" + solidBlocks
                + " in " + Form.duration((long) (M.ms() - startMs), 1));
        Iris.info("genhash world=" + world.getName() + " global=" + Long.toHexString(globalHash)
                + " chunks=" + (side * side) + " solidBlocks=" + solidBlocks + " -> " + out.getAbsolutePath());
    }

    private static long genHashMix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Director(description = "Update the pack of a world (UNSAFE!)", name = "update-world", aliases = "^world")
    public void updateWorld(
            @Param(description = "The world to update", contextual = true)
            World world,
            @Param(description = "The pack to install into the world", contextual = true, aliases = "dimension")
            IrisDimension pack,
            @Param(description = "Make sure to make a backup & read the warnings first!", defaultValue = "false", aliases = "c")
            boolean confirm,
            @Param(description = "Should Iris download the pack again for you", defaultValue = "false", name = "fresh-download", aliases = {"fresh", "new"})
            boolean freshDownload
    ) {
        if (!confirm) {
            sender().sendMessage(new String[]{
                    C.RED + "You should always make a backup before using this",
                    C.YELLOW + "Issues caused by this can be, but are not limited to:",
                    C.YELLOW + " - Broken chunks (cut-offs) between old and new chunks (before & after the update)",
                    C.YELLOW + " - Regenerated chunks that do not fit in with the old chunks",
                    C.YELLOW + " - Structures not spawning again when regenerating",
                    C.YELLOW + " - Caves not lining up",
                    C.YELLOW + " - Terrain layers not lining up",
                    C.RED + "Now that you are aware of the risks, and have made a back-up:",
                    C.RED + "/iris developer update-world " + world.getName() + " " + pack.getLoadKey() + " confirm=true"
            });
            return;
        }

        File folder = world.getWorldFolder();
        folder.mkdirs();

        if (freshDownload) {
            Iris.service(StudioSVC.class).downloadSearch(sender(), pack.getLoadKey(), true);
        }

        Iris.service(StudioSVC.class).installIntoWorld(sender(), pack.getLoadKey(), folder);
    }

    @Director(description = "Test")
    public void mantle(@Param(defaultValue = "false") boolean plate, @Param(defaultValue = "21474836474") String name) throws Throwable {
        var base = Iris.instance.getDataFile("dump", "pv." + name + ".ttp.lz4b.bin");
        var section = Iris.instance.getDataFile("dump", "pv." + name + ".section.bin");

        if (plate) {
            try (var in = CountingDataInputStream.wrap(new BufferedInputStream(new FileInputStream(base)))) {
                TectonicPlate.read(1088, in, true, IrisEngineMantle.createRuntimeDataAdapter(), IrisEngineMantle.createRuntimeHooks());
            } catch (Throwable e) {
                e.printStackTrace();
            }
        } else Matter.read(section);
        if (!TectonicPlate.hasError())
            Iris.info("Read " + (plate ? base : section).length() + " bytes from " + (plate ? base : section).getAbsolutePath());
    }

    @Director(description = "Test")
    public void packBenchmark(
            @Param(description = "The pack to bench", aliases = {"pack"}, defaultValue = "overworld")
            IrisDimension dimension,
            @Param(description = "Radius in regions", defaultValue = "2048")
            int radius,
            @Param(description = "Open GUI while benchmarking", defaultValue = "false")
            boolean gui
    ) {
        new IrisPackBenchmarking(dimension, radius, gui);
    }

    @Director(description = "Upgrade to another Minecraft version")
    public void upgrade(
            @Param(description = "The version to upgrade to", defaultValue = "latest") DataVersion version) {
        sender().sendMessage(C.GREEN + "Upgrading to " + version.getVersion() + "...");
        ServerConfigurator.installDataPacks(version.get(), false);
        sender().sendMessage(C.GREEN + "Done upgrading! You can now update your server version to " + version.getVersion());
    }

    @Director(description = "test")
    public void mca (
            @Param(description = "String") String world) {
        try {
            File[] McaFiles = new File(world, "region").listFiles((dir, name) -> name.endsWith(".mca"));
            for (File mca : McaFiles) {
                MCAFile MCARegion = MCAUtil.read(mca);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Director(description = "Delete nearby chunk blocks for regen testing", name = "delete-chunk", aliases = {"dc"}, origin = DirectorOrigin.PLAYER, sync = true)
    public void deleteChunk(
            @Param(description = "Radius in chunks around your current chunk", defaultValue = "0")
            int radius
    ) {
        if (radius < 0) {
            sender().sendMessage(C.RED + "Radius must be 0 or greater.");
            return;
        }

        World world = player().getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world.");
            return;
        }

        PlatformChunkGenerator access = IrisToolbelt.access(world);
        if (access == null || access.getEngine() == null) {
            sender().sendMessage(C.RED + "The engine access for this world is null.");
            return;
        }

        art.arcane.volmlib.util.mantle.runtime.Mantle mantle = access.getEngine().getMantle().getMantle();
        int centerX = player().getLocation().getBlockX() >> 4;
        int centerZ = player().getLocation().getBlockZ() >> 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        int processed = 0;
        int failed = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerX + x;
                int chunkZ = centerZ + z;
                try {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof Player)) {
                            entity.remove();
                        }
                    }
                    for (int xx = 0; xx < 16; xx++) {
                        for (int zz = 0; zz < 16; zz++) {
                            for (int yy = minY; yy < maxY; yy++) {
                                chunk.getBlock(xx, yy, zz).setType(org.bukkit.Material.AIR, false);
                            }
                        }
                    }
                    mantle.deleteChunk(chunkX, chunkZ);
                    processed++;
                } catch (Throwable e) {
                    failed++;
                    Iris.reportError(e);
                }
            }
        }

        if (failed == 0) {
            sender().sendMessage(C.GREEN + "Deleted blocks in " + C.GOLD + processed + C.GREEN + "/" + C.GOLD + total + C.GREEN + " chunk(s).");
        } else {
            sender().sendMessage(C.YELLOW + "Deleted blocks in " + C.GOLD + processed + C.YELLOW + "/" + C.GOLD + total + C.YELLOW + " chunk(s); " + C.RED + failed + C.YELLOW + " failed.");
        }
    }

    @Director(description = "Test", aliases = {"ip"})
    public void network() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(networkInterfaces)) {
                Iris.info("Display Name: %s", ni.getDisplayName());
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                for (InetAddress ia : Collections.list(inetAddresses)) {
                    Iris.info("IP: %s", ia.getHostAddress());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Regen ---

    @Director(name = "regen", aliases = {"rg"}, description = "Regenerate nearby chunks using Iris generation", origin = DirectorOrigin.PLAYER, sync = true)
    public void regen(
            @Param(name = "radius", description = "The radius of nearby chunks", defaultValue = "5")
            int radius
    ) {
        if (radius < 0) {
            sender().sendMessage(C.RED + "Radius must be 0 or greater.");
            return;
        }

        World world = player().getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "You must be in an Iris world to use regen.");
            return;
        }

        if (INMS.get().isBukkit()) {
            sender().sendMessage(C.RED + "Regen requires the native chunk system; it is unavailable in Bukkit fallback mode.");
            return;
        }

        Engine engine = IrisToolbelt.access(world).getEngine();
        if (engine == null) {
            sender().sendMessage(C.RED + "The engine access for this world is null. Generate nearby chunks first.");
            return;
        }

        int centerX = player().getLocation().getBlockX() >> 4;
        int centerZ = player().getLocation().getBlockZ() >> 4;
        int chunks = (radius * 2 + 1) * (radius * 2 + 1);
        PregenTask task = PregenTask.builder()
                .center(new Position2(centerX << 4, centerZ << 4))
                .radiusX(radius << 4)
                .radiusZ(radius << 4)
                .gui(false)
                .build();

        sender().sendMessage(C.GREEN + "Regen started: " + C.GOLD + chunks + C.GREEN
                + " chunk(s) around " + C.GOLD + centerX + "," + centerZ + C.GREEN
                + ". Chunks purge and regenerate through the async pipeline.");
        Iris.info("Regen run start: world=" + world.getName()
                + " center=" + centerX + "," + centerZ
                + " radius=" + radius
                + " chunks=" + chunks);

        IrisToolbelt.pregenerate(task, new RegenPregenMethod(world), engine, false);
    }

}
