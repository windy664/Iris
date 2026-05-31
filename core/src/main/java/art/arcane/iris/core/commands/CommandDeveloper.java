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
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.datapack.DataVersion;
import art.arcane.iris.core.service.IrisEngineSVC;
import art.arcane.iris.core.service.StudioSVC;
import art.arcane.iris.core.tools.IrisPackBenchmarking;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngineMantle;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.platform.ChunkReplacementListener;
import art.arcane.iris.engine.platform.ChunkReplacementOptions;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorContext;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.DirectorHelp;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.iris.util.common.director.specialhandlers.NullableDimensionHandler;
import art.arcane.iris.util.common.misc.RegenRuntime;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.CountingDataInputStream;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.iris.util.nbt.common.mca.MCAFile;
import art.arcane.iris.util.nbt.common.mca.MCAUtil;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.parallel.SyncExecutor;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.iris.util.common.scheduling.jobs.Job;
import lombok.SneakyThrows;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.lang.RandomStringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Director(name = "Developer", origin = DirectorOrigin.BOTH, description = "Iris World Manager", aliases = {"dev"})
public class CommandDeveloper implements DirectorExecutor {
    @Director(description = "Show help tree for this command group", aliases = {"?"})
    public void help() {
        DirectorHelp.print(sender(), getClass());
    }

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

    @Director(description = "QOL command to open an overworld studio world", sync = true)
    public void so() {
        sender().sendMessage(C.GREEN + "Opening studio for the \"Overworld\" pack (seed: 1337)");
        Iris.service(StudioSVC.class).open(sender(), 1337, "overworld");
    }

    @Director(description = "Set aura spins")
    public void aura(
            @Param(description = "The h color value", defaultValue = "-20")
            int h,
            @Param(description = "The s color value", defaultValue = "7")
            int s,
            @Param(description = "The b color value", defaultValue = "8")
            int b
    ) {
        IrisSettings.get().getGeneral().setSpinh(h);
        IrisSettings.get().getGeneral().setSpins(s);
        IrisSettings.get().getGeneral().setSpinb(b);
        IrisSettings.get().forceSave();
        sender().sendMessage("<rainbow>Aura Spins updated to " + h + " " + s + " " + b);
    }

    @Director(description = "Bitwise calculations")
    public void bitwise(
            @Param(description = "The first value to run calculations on")
            int value1,
            @Param(description = "The operator: | & ^ << >> %")
            String operator,
            @Param(description = "The second value to run calculations on")
            int value2
    ) {
        Integer v = null;
        switch (operator) {
            case "|" -> v = value1 | value2;
            case "&" -> v = value1 & value2;
            case "^" -> v = value1 ^ value2;
            case "%" -> v = value1 % value2;
            case ">>" -> v = value1 >> value2;
            case "<<" -> v = value1 << value2;
        }
        if (v == null) {
            sender().sendMessage(C.RED + "The operator you entered: (" + operator + ") is invalid!");
            return;
        }
        sender().sendMessage(C.GREEN + "" + value1 + " " + C.GREEN + operator.replaceAll("<", "≺").replaceAll(">", "≻").replaceAll("%", "％") + " " + C.GREEN + value2 + C.GREEN + " returns " + C.GREEN + v);
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

    @Director(description = "Dev cmd to fix all the broken objects caused by faulty shrinkwarp")
    public void fixObjects(
            @Param(aliases = "dimension", description = "The dimension type to create the world with")
            IrisDimension type
    ) {
        if (type == null) {
            sender().sendMessage("Type cant be null?");
            return;
        }

        IrisData dm = IrisData.get(Iris.instance.getDataFolder("packs", type.getLoadKey()));
        var loader = dm.getObjectLoader();
        var objects = loader.getPossibleKeys();
        var sender = sender();

        sender.sendMessage(C.IRIS + "Found " + objects.length + " objects in " + type.getLoadKey());

        final int total = objects.length;
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger changed = new AtomicInteger();

        new Job() {
            @Override
            public String getName() {
                return "Fixing Objects";
            }

            @Override
            public void execute() {
                Arrays.stream(loader.getPossibleKeys()).parallel()
                        .map(loader::load)
                        .forEach(obj -> {
                            if (obj == null) {
                                completeWork();
                                return;
                            }

                            obj.shrinkwrap();
                            if (obj.getShrinkOffset().isZero()) {
                                completeWork();
                                return;
                            }

                            try {
                                obj.write(obj.getLoadFile());
                                completeWork();
                                changed.incrementAndGet();
                            } catch (IOException e) {
                                Iris.error("Failed to write object " + obj.getLoadKey());
                                e.printStackTrace();
                            }
                        });
            }

            @Override
            public void completeWork() {
                completed.incrementAndGet();
            }

            @Override
            public int getTotalWork() {
                return total;
            }

            @Override
            public int getWorkCompleted() {
                return completed.get();
            }
        }.execute(sender, () -> {
            var failed = total - completed.get();
            if (failed != 0) sender.sendMessage(C.IRIS + "" + failed + " objects failed!");
            if (changed.get() != 0) sender.sendMessage(C.IRIS + "" + changed.get() + " objects had their offsets changed!");
            else sender.sendMessage(C.IRIS + "No objects had their offsets changed!");
        });
    }

    @Director(description = "Test")
    public void mantle(@Param(defaultValue = "false") boolean plate, @Param(defaultValue = "21474836474") String name) throws Throwable {
        var base = Iris.instance.getDataFile("dump", "pv." + name + ".ttp.lz4b.bin");
        var section = Iris.instance.getDataFile("dump", "pv." + name + ".section.bin");

        //extractSection(base, section, 5604930, 4397);

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

    private void extractSection(File source, File target, long offset, int length) throws IOException {
        var raf = new RandomAccessFile(source, "r");
        var bytes = new byte[length];
        raf.seek(offset);
        raf.readFully(bytes);
        raf.close();
        Files.write(target.toPath(), bytes);
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

    @Director(description = "Delete nearby chunk blocks for regen testing", name = "delete-chunk", aliases = {"delchunk", "dc"}, origin = DirectorOrigin.PLAYER, sync = true)
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

    @Director(description = "UnloadChunks for good reasons.")
    public void unloadchunks() {
        List<World> IrisWorlds = new ArrayList<>();
        int chunksUnloaded = 0;
        for (World world : Bukkit.getWorlds()) {
            try {
                if (IrisToolbelt.access(world).getEngine() != null) {
                    IrisWorlds.add(world);
                }
            } catch (Exception e) {
                // no
            }
        }

        for (World world : IrisWorlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (chunk.isLoaded()) {
                    chunk.unload();
                    chunksUnloaded++;
                }
            }
        }
        Iris.info(C.IRIS + "Chunks Unloaded: " + chunksUnloaded);

    }

    @Director
    public void objects(@Param(defaultValue = "overworld") IrisDimension dimension) {
        var loader = dimension.getLoader().getObjectLoader();
        var sender = sender();
        var keys = loader.getPossibleKeys();
        var burst = MultiBurst.burst.burst(keys.length);
        AtomicInteger failed = new AtomicInteger();
        for (String key : keys) {
            burst.queue(() -> {
                if (loader.load(key) == null)
                    failed.incrementAndGet();
            });
        }
        burst.complete();
        sender.sendMessage(C.RED + "Failed to load " + failed.get() + " of " + keys.length + " objects");
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

    @Director(description = "Test the compression algorithms")
    public void compression(
            @Param(description = "base IrisWorld") World world,
            @Param(description = "raw TectonicPlate File") String path,
            @Param(description = "Algorithm to Test") String algorithm,
            @Param(description = "Amount of Tests") int amount,
            @Param(description = "Is versioned", defaultValue = "false") boolean versioned) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            sender().sendMessage(C.RED + "This is not an Iris world. Iris worlds: " + String.join(", ", Bukkit.getServer().getWorlds().stream().filter(IrisToolbelt::isIrisWorld).map(World::getName).toList()));
            return;
        }

        File file = new File(path);
        if (!file.exists()) return;

        Engine engine = IrisToolbelt.access(world).getEngine();
        if(engine != null) {
            int height = engine.getTarget().getHeight();
            ExecutorService service = Executors.newFixedThreadPool(1);
            VolmitSender sender = sender();
            service.submit(() -> {
                try {
                    CountingDataInputStream raw = CountingDataInputStream.wrap(new FileInputStream(file));
                    TectonicPlate<Matter> plate = TectonicPlate.read(height, raw, versioned, IrisEngineMantle.createRuntimeDataAdapter(), IrisEngineMantle.createRuntimeHooks());
                    raw.close();

                    double d1 = 0;
                    double d2 = 0;
                    long size = 0;
                    File folder = new File("tmp");
                    folder.mkdirs();
                    for (int i = 0; i < amount; i++) {
                        File tmp = new File(folder, RandomStringUtils.randomAlphanumeric(10) + "." + algorithm + ".bin");
                        DataOutputStream dos = createOutput(tmp, algorithm);
                        long start = System.currentTimeMillis();
                        plate.write(dos);
                        dos.close();
                        d1 += System.currentTimeMillis() - start;
                        if (size == 0)
                            size = tmp.length();
                        start = System.currentTimeMillis();
                        CountingDataInputStream din = createInput(tmp, algorithm);
                        TectonicPlate.read(height, din, true, IrisEngineMantle.createRuntimeDataAdapter(), IrisEngineMantle.createRuntimeHooks());
                        din.close();
                        d2 += System.currentTimeMillis() - start;
                        tmp.delete();
                    }
                    IO.delete(folder);
                    sender.sendMessage(algorithm + " is " + Form.fileSize(size) + " big after compression");
                    sender.sendMessage(algorithm + " Took " + d2/amount + "ms to read");
                    sender.sendMessage(algorithm + " Took " + d1/amount + "ms to write");
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
            service.shutdown();
		} else {
            Iris.info(C.RED + "Engine is null!");
        }
    }

    private CountingDataInputStream createInput(File file, String algorithm) throws Throwable {
        FileInputStream in = new FileInputStream(file);

        return CountingDataInputStream.wrap(switch (algorithm) {
            case "gzip" -> new GZIPInputStream(in);
            case "lz4f" -> new LZ4FrameInputStream(in);
            case "lz4b" -> new LZ4BlockInputStream(in);
            default -> throw new IllegalStateException("Unexpected value: " + algorithm);
        });
    }

    private DataOutputStream createOutput(File file, String algorithm) throws Throwable {
        FileOutputStream out = new FileOutputStream(file);

        return new DataOutputStream(switch (algorithm) {
            case "gzip" -> new GZIPOutputStream(out);
            case "lz4f" -> new LZ4FrameOutputStream(out);
            case "lz4b" -> new LZ4BlockOutputStream(out);
            default -> throw new IllegalStateException("Unexpected value: " + algorithm);
        });
    }

    // --- Regen ---

    private static final long REGEN_HEARTBEAT_MS = 5000L;
    private static final int REGEN_MAX_ATTEMPTS = 2;
    private static final int REGEN_STACK_LIMIT = 20;
    private static final long REGEN_STALL_DUMP_IDLE_MS = 30000L;
    private static final long REGEN_STALL_ABORT_IDLE_MS = 600000L;
    private static final long REGEN_STACK_DUMP_INTERVAL_MS = 10000L;
    private static final int REGEN_PROGRESS_BAR_WIDTH = 44;
    private static final long REGEN_PROGRESS_UPDATE_MS = 200L;
    private static final int REGEN_ACTION_PULSE_TICKS = 20;
    private static final int REGEN_DISPLAY_FINAL_TICKS = 60;

    @Director(name = "regen", aliases = {"rg"}, description = "Regenerate nearby chunks using Iris generation", origin = DirectorOrigin.PLAYER, sync = true)
    public void regen(
            @Param(name = "radius", description = "The radius of nearby chunks", defaultValue = "5")
            int radius,
            @Param(name = "mode", aliases = {"scope", "profile"}, description = "Regen mode: terrain or full", defaultValue = "full")
            String mode
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

        RegenMode regenMode = RegenMode.parse(mode);
        if (regenMode == null) {
            sender().sendMessage(C.RED + "Unknown regen mode \"" + mode + "\". Use mode=terrain or mode=full.");
            return;
        }

        VolmitSender sender = sender();
        int centerX = player().getLocation().getBlockX() >> 4;
        int centerZ = player().getLocation().getBlockZ() >> 4;
        int threadCount = resolveRegenThreadCount(regenMode);
        List<Position2> targets = buildRegenTargets(centerX, centerZ, radius);
        int chunks = targets.size();
        String runId = world.getName() + "-" + System.currentTimeMillis();
        RegenDisplay display = createRegenDisplay(sender, regenMode);

        sender.sendMessage(C.GREEN + "Regen started (" + C.GOLD + regenMode.id() + C.GREEN + "): "
                + C.GOLD + chunks + C.GREEN + " chunks, "
                + C.GOLD + threadCount + C.GREEN + " worker(s). "
                + C.GRAY + "Progress is shown on-screen.");
        if (regenMode == RegenMode.TERRAIN) {
            Iris.warn("Regen running in terrain mode; mantle overlay/object replay is skipped. Use mode=full to regenerate objects.");
        }

        Iris.info("Regen run start: id=" + runId
                + " world=" + world.getName()
                + " center=" + centerX + "," + centerZ
                + " radius=" + radius
                + " mode=" + regenMode.id()
                + " workers=" + threadCount
                + " chunks=" + chunks);
        if (IrisSettings.get().getGeneral().isDebug()) {
            Iris.info("Regen mode config: id=" + runId
                    + " mode=" + regenMode.id()
                    + " maintenance=" + regenMode.usesMaintenance()
                    + " bypassMantle=" + regenMode.bypassMantleStages()
                    + " passes=" + regenMode.passCount()
                    + " fullMode=" + regenMode.isFullMode()
                    + " diagnostics=" + regenMode.logChunkDiagnostics());
        }

        String orchestratorName = "Iris-Regen-Orchestrator-" + runId;
        Thread orchestrator = new Thread(() -> runRegenOrchestrator(sender, world, targets, threadCount, regenMode, runId, display), orchestratorName);
        orchestrator.setDaemon(true);
        try {
            orchestrator.start();
            if (IrisSettings.get().getGeneral().isDebug()) {
                Iris.info("Regen worker dispatched on dedicated thread=" + orchestratorName + " id=" + runId + ".");
            }
        } catch (Throwable e) {
            sender.sendMessage(C.RED + "Failed to start regen worker thread. See console.");
            closeRegenDisplay(display, 0);
            Iris.reportError(e);
        }
    }

    private int resolveRegenThreadCount(RegenMode mode) {
        int workers = resolveRegenWorkerThreads();
        boolean folia = J.isFolia();
        if (mode == RegenMode.TERRAIN) {
            int cap = folia ? Math.min(workers * 4, 96) : Math.min(workers * 2, 64);
            return Math.max(folia ? 16 : 8, cap);
        }
        int cap = folia ? Math.min(workers * 2, 64) : Math.min(workers, 32);
        return Math.max(folia ? 8 : 4, cap);
    }

    private int resolveRegenWorkerThreads() {
        try {
            Class<?> moonriseCommonClass = Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon");
            java.lang.reflect.Field workerPoolField = moonriseCommonClass.getDeclaredField("WORKER_POOL");
            Object workerPool = workerPoolField.get(null);
            Object coreThreads = workerPool.getClass().getDeclaredMethod("getCoreThreads").invoke(workerPool);
            if (coreThreads instanceof Thread[] threadsArray && threadsArray.length > 0) {
                return threadsArray.length;
            }
        } catch (Throwable ignored) {
        }
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int configured = Math.max(1, IrisSettings.get().getConcurrency().getWorldGenThreads());
        return Math.max(cpus, configured);
    }

    private List<Position2> buildRegenTargets(int centerX, int centerZ, int radius) {
        int expected = (radius * 2 + 1) * (radius * 2 + 1);
        List<Position2> targets = new ArrayList<>(expected);
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) {
                        continue;
                    }
                    targets.add(new Position2(centerX + x, centerZ + z));
                }
            }
        }
        return targets;
    }

    private void runRegenOrchestrator(
            VolmitSender sender,
            World world,
            List<Position2> targets,
            int threadCount,
            RegenMode mode,
            String runId,
            RegenDisplay display
    ) {
        long runStart = System.currentTimeMillis();
        AtomicBoolean setupDone = new AtomicBoolean(false);
        AtomicReference<String> setupPhase = new AtomicReference<>("bootstrap");
        AtomicLong setupPhaseSince = new AtomicLong(runStart);
        Thread setupWatchdog = createRegenSetupWatchdog(world, runId, setupDone, setupPhase, setupPhaseSince);
        setupWatchdog.start();
        boolean displayTerminal = false;

        Set<Thread> regenThreads = ConcurrentHashMap.newKeySet();
        AtomicInteger regenThreadCounter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "Iris-Regen-" + runId + "-" + regenThreadCounter.incrementAndGet());
            thread.setDaemon(true);
            regenThreads.add(thread);
            return thread;
        };

        try {
            setRegenSetupPhase(setupPhase, setupPhaseSince, "touch-context", world, runId);
            updateRegenSetupDisplay(display, mode, "Touching command context", 1, 6);
            DirectorContext.touch(sender);
            if (mode.usesMaintenance()) {
                setRegenSetupPhase(setupPhase, setupPhaseSince, "enter-maintenance", world, runId);
                updateRegenSetupDisplay(display, mode, "Entering maintenance", 2, 6);
                IrisToolbelt.beginWorldMaintenance(world, "regen:" + mode.id(), mode.bypassMantleStages());
            } else {
                setRegenSetupPhase(setupPhase, setupPhaseSince, "maintenance-skip", world, runId);
                updateRegenSetupDisplay(display, mode, "Skipping maintenance", 2, 6);
            }

            ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadCount, threadFactory);
            try (SyncExecutor executor = new SyncExecutor(20)) {
                setRegenSetupPhase(setupPhase, setupPhaseSince, "resolve-platform", world, runId);
                updateRegenSetupDisplay(display, mode, "Resolving platform", 3, 6);
                PlatformChunkGenerator platform = IrisToolbelt.access(world);
                setRegenSetupPhase(setupPhase, setupPhaseSince, "validate-engine", world, runId);
                updateRegenSetupDisplay(display, mode, "Validating engine", 4, 6);
                if (platform == null || platform.getEngine() == null) {
                    Iris.warn("Regen aborted: engine access is null for world=" + world.getName() + " id=" + runId + ".");
                    completeRegenDisplay(display, mode, true, C.RED + "Engine access is null. Generate nearby chunks first.");
                    displayTerminal = true;
                    return;
                }

                setRegenSetupPhase(setupPhase, setupPhaseSince, "prepare-options", world, runId);
                updateRegenSetupDisplay(display, mode, "Preparing chunk replacement", 5, 6);

                setRegenSetupPhase(setupPhase, setupPhaseSince, "dispatch", world, runId);
                updateRegenSetupDisplay(display, mode, "Dispatching chunk workers", 6, 6);
                RegenSummary summary = executeRegenQueue(sender, world, platform, targets, executor, pool, regenThreads, mode, runId, 1, 1, runStart, display);

                if (summary == null) {
                    completeRegenDisplay(display, mode, true, C.RED + "Regen failed before pass execution.");
                    displayTerminal = true;
                    return;
                }

                long totalRuntime = System.currentTimeMillis() - runStart;
                if (summary.failedChunks() <= 0) {
                    completeRegenDisplay(display, mode, false, C.GREEN + "Complete " + C.GOLD + summary.successChunks()
                            + C.GREEN + "/" + C.GOLD + summary.totalChunks() + C.GREEN + " in " + C.GOLD + totalRuntime + "ms");
                    displayTerminal = true;
                    return;
                }

                String failureDetail = C.RED + "Failed chunks " + C.GOLD + summary.failedChunks() + C.RED
                        + ", retries " + C.GOLD + summary.retryCount()
                        + C.RED + ", runtime " + C.GOLD + totalRuntime + "ms";
                if (summary.failurePhaseSummary() != null && !summary.failurePhaseSummary().isBlank() && !"none".equals(summary.failurePhaseSummary())) {
                    failureDetail = failureDetail + C.DARK_GRAY + " [phase " + summary.failurePhaseSummary() + "]";
                }
                if (!summary.failedPreview().isEmpty()) {
                    failureDetail = failureDetail + C.DARK_GRAY + " [" + summary.failedPreview() + "]";
                }
                completeRegenDisplay(display, mode, true, failureDetail);
                displayTerminal = true;
            } finally {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completeRegenDisplay(display, mode, true, C.RED + "Regen interrupted.");
            displayTerminal = true;
            Iris.warn("Regen run interrupted: id=" + runId + " world=" + world.getName());
        } catch (Throwable e) {
            String failureDetail = C.RED + "Regen failed. Check console.";
            if (e.getMessage() != null && e.getMessage().contains("stalled")) {
                failureDetail = C.RED + "Regen stalled. Try smaller radius or terrain mode.";
            }
            completeRegenDisplay(display, mode, true, failureDetail);
            displayTerminal = true;
            Iris.reportError(e);
            e.printStackTrace();
        } finally {
            setupDone.set(true);
            setupWatchdog.interrupt();
            if (mode.usesMaintenance()) {
                IrisToolbelt.endWorldMaintenance(world, "regen:" + mode.id());
            }
            if (!displayTerminal) {
                closeRegenDisplay(display, REGEN_DISPLAY_FINAL_TICKS);
            }
            DirectorContext.remove();
            Iris.info("Regen run closed: id=" + runId + " world=" + world.getName() + " totalMs=" + (System.currentTimeMillis() - runStart));
        }
    }

    private RegenSummary executeRegenQueue(
            VolmitSender sender,
            World world,
            PlatformChunkGenerator platform,
            List<Position2> targets,
            SyncExecutor executor,
            ThreadPoolExecutor pool,
            Set<Thread> regenThreads,
            RegenMode mode,
            String runId,
            int passIndex,
            int passCount,
            long runStart,
            RegenDisplay display
    ) throws InterruptedException {
        ArrayDeque<RegenChunkTask> pending = new ArrayDeque<>(targets.size());
        long queueTime = System.currentTimeMillis();
        for (Position2 target : targets) {
            pending.addLast(new RegenChunkTask(target.getX(), target.getZ(), 1, queueTime));
        }

        ConcurrentMap<String, RegenActiveTask> activeTasks = new ConcurrentHashMap<>();
        ExecutorCompletionService<RegenChunkResult> completion = new ExecutorCompletionService<>(pool);
        List<Position2> failedChunks = new ArrayList<>();
        Map<String, Integer> failurePhaseCounts = new HashMap<>();

        int totalChunks = targets.size();
        int successChunks = 0;
        int failedCount = 0;
        int retryCount = 0;
        int overlayChunks = 0;
        int overlayObjectChunks = 0;
        int overlayBlocks = 0;
        long submittedTasks = 0L;
        long finishedTasks = 0L;
        int completedChunks = 0;
        int inFlight = 0;
        AtomicLong lastSignalMs = new AtomicLong(System.currentTimeMillis());
        long lastDump = 0L;
        long lastProgressUiMs = 0L;
        lastProgressUiMs = updateRegenProgressAction(
                sender,
                display,
                mode,
                passIndex,
                passCount,
                completedChunks,
                totalChunks,
                inFlight,
                pending.size(),
                false,
                false,
                false,
                true,
                "Queue initialized",
                lastProgressUiMs
        );

        while (inFlight < pool.getMaximumPoolSize() && !pending.isEmpty()) {
            RegenChunkTask task = pending.removeFirst();
            completion.submit(() -> runRegenChunk(task, world, platform, executor, activeTasks, mode, runId, lastSignalMs));
            inFlight++;
            submittedTasks++;
        }

        while (completedChunks < totalChunks) {
            Future<RegenChunkResult> future = completion.poll(REGEN_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
            if (future == null) {
                long now = System.currentTimeMillis();
                long idleMs = Math.max(0L, now - lastSignalMs.get());
                boolean stalled = idleMs >= REGEN_HEARTBEAT_MS;
                String phaseSummary = summarizeActivePhases(activeTasks);

                Iris.warn("Regen heartbeat: id=" + runId
                        + " completed=" + completedChunks + "/" + totalChunks
                        + " remaining=" + (totalChunks - completedChunks)
                        + " queued=" + pending.size()
                        + " inFlight=" + inFlight
                        + " submitted=" + submittedTasks
                        + " finishedTasks=" + finishedTasks
                        + " retries=" + retryCount
                        + " failed=" + failedCount
                        + " poolActive=" + pool.getActiveCount()
                        + " poolQueue=" + pool.getQueue().size()
                        + " poolDone=" + pool.getCompletedTaskCount()
                        + " idleMs=" + idleMs
                        + " phases=" + phaseSummary
                        + " activeTasks=" + formatActiveTasks(activeTasks));
                lastProgressUiMs = updateRegenProgressAction(
                        sender,
                        display,
                        mode,
                        passIndex,
                        passCount,
                        completedChunks,
                        totalChunks,
                        inFlight,
                        pending.size(),
                        stalled,
                        false,
                        false,
                        true,
                        stalled ? "Waiting in phase " + phaseSummary : "Waiting for chunk result",
                        lastProgressUiMs
                );

                if (idleMs >= REGEN_STALL_DUMP_IDLE_MS && now - lastDump >= REGEN_STACK_DUMP_INTERVAL_MS) {
                    lastDump = now;
                    Iris.warn("Regen appears stalled; dumping worker stack traces for id=" + runId + ".");
                    dumpRegenWorkerStacks(regenThreads, world.getName());
                }
                if (idleMs >= REGEN_STALL_ABORT_IDLE_MS) {
                    updateRegenProgressAction(
                            sender,
                            display,
                            mode,
                            passIndex,
                            passCount,
                            completedChunks,
                            totalChunks,
                            inFlight,
                            pending.size(),
                            true,
                            true,
                            true,
                            true,
                            "Stalled in phase " + phaseSummary,
                            lastProgressUiMs
                    );
                    throw new IllegalStateException("Regen stalled with no chunk heartbeat or completion for "
                            + idleMs
                            + "ms (id=" + runId
                            + ", mode=" + mode.id()
                            + ", completed=" + completedChunks
                            + "/" + totalChunks
                            + ", inFlight=" + inFlight
                            + ", queued=" + pending.size()
                            + ", phase=" + phaseSummary
                            + ").");
                }
                continue;
            }

            RegenChunkResult result;
            try {
                result = future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("Regen worker failed unexpectedly for run " + runId, cause);
            }

            inFlight--;
            finishedTasks++;
            long duration = result.finishedAtMs() - result.startedAtMs();
            lastSignalMs.set(System.currentTimeMillis());

            if (result.success()) {
                completedChunks++;
                successChunks++;
                if (result.overlayAppliedBlocks() > 0) {
                    overlayChunks++;
                }
                if (result.overlayObjectKeys() > 0) {
                    overlayObjectChunks++;
                }
                overlayBlocks += result.overlayAppliedBlocks();
                if (result.task().attempt() > 1) {
                    Iris.warn("Regen chunk recovered after retry: id=" + runId
                            + " chunk=" + result.task().chunkX() + "," + result.task().chunkZ()
                            + " attempt=" + result.task().attempt()
                            + " durationMs=" + duration);
                } else if (duration >= 5000L) {
                    Iris.warn("Regen chunk slow: id=" + runId
                            + " chunk=" + result.task().chunkX() + "," + result.task().chunkZ()
                            + " durationMs=" + duration
                            + " loadedAtStart=" + result.loadedAtStart());
                }
            } else if (result.task().attempt() < REGEN_MAX_ATTEMPTS) {
                retryCount++;
                RegenChunkTask retryTask = result.task().retry(System.currentTimeMillis());
                pending.addLast(retryTask);
                Iris.warn("Regen chunk retry scheduled: id=" + runId
                        + " chunk=" + result.task().chunkX() + "," + result.task().chunkZ()
                        + " failedAttempt=" + result.task().attempt()
                        + " nextAttempt=" + retryTask.attempt()
                        + " phase=" + result.failurePhase()
                        + " error=" + result.errorSummary());
            } else {
                completedChunks++;
                failedCount++;
                Position2 failed = new Position2(result.task().chunkX(), result.task().chunkZ());
                failedChunks.add(failed);
                String failurePhase = result.failurePhase() == null || result.failurePhase().isBlank()
                        ? "unknown"
                        : result.failurePhase();
                failurePhaseCounts.merge(failurePhase, 1, Integer::sum);
                Iris.warn("Regen chunk failed terminally: id=" + runId
                        + " chunk=" + result.task().chunkX() + "," + result.task().chunkZ()
                        + " attempts=" + result.task().attempt()
                        + " phase=" + failurePhase
                        + " error=" + result.errorSummary());
                if (result.error() != null) {
                    Iris.reportError(result.error());
                }
            }

            while (inFlight < pool.getMaximumPoolSize() && !pending.isEmpty()) {
                RegenChunkTask task = pending.removeFirst();
                completion.submit(() -> runRegenChunk(task, world, platform, executor, activeTasks, mode, runId, lastSignalMs));
                inFlight++;
                submittedTasks++;
            }

            String phaseSummary = summarizeActivePhases(activeTasks);
            lastProgressUiMs = updateRegenProgressAction(
                    sender,
                    display,
                    mode,
                    passIndex,
                    passCount,
                    completedChunks,
                    totalChunks,
                    inFlight,
                    pending.size(),
                    false,
                    false,
                    false,
                    false,
                    phaseSummary.equals("idle") ? "Generating chunks" : "Generating chunks in " + phaseSummary,
                    lastProgressUiMs
            );
        }

        long runtimeMs = System.currentTimeMillis() - runStart;
        String preview = formatFailedChunkPreview(failedChunks);
        String failurePhaseSummary = formatFailurePhaseSummary(failurePhaseCounts);
        Iris.info("Regen run complete: id=" + runId
                + " world=" + world.getName()
                + " total=" + totalChunks
                + " success=" + successChunks
                + " failed=" + failedCount
                + " retries=" + retryCount
                + " submittedTasks=" + submittedTasks
                + " finishedTasks=" + finishedTasks
                + " overlayChunks=" + overlayChunks
                + " overlayObjectChunks=" + overlayObjectChunks
                + " overlayBlocks=" + overlayBlocks
                + " failurePhases=" + failurePhaseSummary
                + " runtimeMs=" + runtimeMs
                + " failedPreview=" + preview);
        updateRegenProgressAction(
                sender,
                display,
                mode,
                passIndex,
                passCount,
                completedChunks,
                totalChunks,
                inFlight,
                pending.size(),
                false,
                true,
                failedCount > 0,
                true,
                failedCount > 0 ? "Completed with failures in " + failurePhaseSummary : "Pass complete",
                lastProgressUiMs
        );
        return new RegenSummary(totalChunks, successChunks, failedCount, retryCount, preview, failurePhaseSummary);
    }

    private long updateRegenProgressAction(
            VolmitSender sender,
            RegenDisplay display,
            RegenMode mode,
            int passIndex,
            int passCount,
            int completed,
            int total,
            int inFlight,
            int queued,
            boolean stalled,
            boolean terminal,
            boolean failed,
            boolean force,
            String detail,
            long lastUiMs
    ) {
        if (display == null && !sender.isPlayer()) {
            return lastUiMs;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastUiMs < REGEN_PROGRESS_UPDATE_MS) {
            return lastUiMs;
        }

        int safePassCount = Math.max(1, passCount);
        int safePassIndex = Math.max(1, Math.min(passIndex, safePassCount));
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        double passProgress = safeCompleted / (double) safeTotal;
        double overallProgress = ((safePassIndex - 1) + passProgress) / safePassCount;
        int percent = (int) Math.round(overallProgress * 100.0D);
        String bar = buildRegenProgressBar(overallProgress);
        C statusColor = failed ? C.RED : terminal ? C.GREEN : stalled ? C.RED : C.AQUA;
        String statusLabel = failed ? "FAILED" : terminal ? "DONE" : stalled ? "STALLED" : "RUN";
        BarColor bossColor = failed ? BarColor.RED : terminal ? BarColor.GREEN : stalled ? BarColor.RED : BarColor.BLUE;
        String title = C.GOLD + "Regen " + mode.id()
                + C.GRAY + " " + statusColor + statusLabel
                + C.GRAY + " " + C.YELLOW + percent + "%"
                + C.DARK_GRAY + " P" + safePassIndex + "/" + safePassCount;
        String action = bar
                + C.GRAY + " " + C.YELLOW + percent + "%"
                + C.DARK_GRAY + " P" + safePassIndex + "/" + safePassCount
                + C.DARK_GRAY + " C" + safeCompleted + "/" + safeTotal
                + C.DARK_GRAY + " Q" + queued
                + C.DARK_GRAY + " F" + inFlight;
        if (detail != null && !detail.isBlank()) {
            action = action + C.GRAY + " | " + C.WHITE + detail;
        }

        if (display != null) {
            updateRegenDisplay(display, overallProgress, bossColor, title, action);
            return now;
        }

        if (sender.isPlayer()) {
            String actionText = action;
            J.runEntity(sender.player(), () -> sender.sendAction(actionText));
        }
        return now;
    }

    private static String buildRegenProgressBar(double progress) {
        int width = REGEN_PROGRESS_BAR_WIDTH;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * width);
        StringBuilder bar = new StringBuilder(width * 3 + 4);
        bar.append(C.DARK_GRAY).append("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? C.GREEN : C.DARK_GRAY).append("|");
        }
        bar.append(C.DARK_GRAY).append("]");
        return bar.toString();
    }

    private RegenDisplay createRegenDisplay(VolmitSender sender, RegenMode mode) {
        if (!sender.isPlayer()) {
            return null;
        }

        Player player = sender.player();
        if (player == null) {
            return null;
        }

        BossBar bossBar = Bukkit.createBossBar(C.GOLD + "Regen " + mode.id(), BarColor.BLUE, BarStyle.SEGMENTED_20);
        bossBar.setProgress(0.0D);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        RegenDisplay display = new RegenDisplay(sender, bossBar);
        String title = C.GOLD + "Regen " + mode.id() + C.GRAY + " " + C.AQUA + "RUN" + C.GRAY + " " + C.YELLOW + "0%";
        String action = buildRegenProgressBar(0.0D) + C.GRAY + " " + C.YELLOW + "0%" + C.GRAY + " | " + C.WHITE + "Preparing setup";
        updateRegenDisplay(display, 0.0D, BarColor.BLUE, title, action);
        pulseRegenDisplay(display);
        return display;
    }

    private void updateRegenSetupDisplay(RegenDisplay display, RegenMode mode, String phase, int step, int totalSteps) {
        if (display == null || display.closed.get()) {
            return;
        }

        int safeTotalSteps = Math.max(1, totalSteps);
        int safeStep = Math.max(0, Math.min(step, safeTotalSteps));
        double setupProgress = Math.max(0.0D, Math.min(0.1D, (safeStep / (double) safeTotalSteps) * 0.1D));
        int percent = (int) Math.round(setupProgress * 100.0D);
        String title = C.GOLD + "Regen " + mode.id() + C.GRAY + " " + C.AQUA + "SETUP" + C.GRAY + " " + C.YELLOW + percent + "%";
        String action = buildRegenProgressBar(setupProgress)
                + C.GRAY + " " + C.YELLOW + percent + "%"
                + C.GRAY + " | " + C.WHITE + phase;
        updateRegenDisplay(display, setupProgress, BarColor.BLUE, title, action);
    }

    private void updateRegenDisplay(RegenDisplay display, double progress, BarColor color, String title, String action) {
        if (display == null || display.closed.get()) {
            return;
        }

        display.progress = Math.max(0.0D, Math.min(1.0D, progress));
        display.color = color == null ? BarColor.BLUE : color;
        display.title = title == null ? "" : title;
        display.actionLine = action == null ? "" : action;

        Player player = display.sender.player();
        if (player == null) {
            closeRegenDisplay(display, 0);
            return;
        }

        boolean scheduled = J.runEntity(player, () -> {
            if (display.closed.get()) {
                return;
            }

            display.bossBar.setProgress(display.progress);
            display.bossBar.setColor(display.color);
            display.bossBar.setTitle(display.title);
            if (!display.actionLine.isBlank()) {
                display.sender.sendAction(display.actionLine);
            }
        });
        if (!scheduled) {
            closeRegenDisplay(display, 0);
        }
    }

    private void pulseRegenDisplay(RegenDisplay display) {
        if (display == null || display.closed.get()) {
            return;
        }

        Player player = display.sender.player();
        if (player == null) {
            closeRegenDisplay(display, 0);
            return;
        }

        boolean scheduled = J.runEntity(player, () -> {
            if (display.closed.get()) {
                return;
            }

            Player activePlayer = display.sender.player();
            if (activePlayer == null || !activePlayer.isOnline()) {
                closeRegenDisplay(display, 0);
                return;
            }

            if (!display.actionLine.isBlank()) {
                display.sender.sendAction(display.actionLine);
            }
            pulseRegenDisplay(display);
        }, REGEN_ACTION_PULSE_TICKS);

        if (!scheduled) {
            closeRegenDisplay(display, 0);
        }
    }

    private void completeRegenDisplay(RegenDisplay display, RegenMode mode, boolean failed, String detail) {
        if (display == null || display.closed.get()) {
            return;
        }

        double progress = failed ? Math.max(0.0D, Math.min(1.0D, display.progress)) : 1.0D;
        int percent = (int) Math.round(progress * 100.0D);
        BarColor color = failed ? BarColor.RED : BarColor.GREEN;
        String status = failed ? C.RED + "FAILED" : C.GREEN + "DONE";
        String title = C.GOLD + "Regen " + mode.id() + C.GRAY + " " + status + C.GRAY + " " + C.YELLOW + percent + "%";
        String action = buildRegenProgressBar(progress) + C.GRAY + " " + C.YELLOW + percent + "%";
        if (detail != null && !detail.isBlank()) {
            action = action + C.GRAY + " | " + C.WHITE + detail;
        }

        updateRegenDisplay(display, progress, color, title, action);
        closeRegenDisplay(display, REGEN_DISPLAY_FINAL_TICKS);
    }

    private void closeRegenDisplay(RegenDisplay display, int delayTicks) {
        if (display == null || display.closed.get()) {
            return;
        }

        Player player = display.sender.player();
        Runnable closeTask = () -> {
            if (!display.closed.compareAndSet(false, true)) {
                return;
            }

            display.bossBar.removeAll();
            display.bossBar.setVisible(false);
            display.sender.sendAction(" ");
        };

        if (player == null) {
            display.closed.set(true);
            return;
        }

        boolean scheduled = delayTicks > 0
                ? J.runEntity(player, closeTask, delayTicks)
                : J.runEntity(player, closeTask);
        if (!scheduled) {
            display.closed.set(true);
        }
    }

    private RegenChunkResult runRegenChunk(
            RegenChunkTask task,
            World world,
            PlatformChunkGenerator platform,
            SyncExecutor executor,
            ConcurrentMap<String, RegenActiveTask> activeTasks,
            RegenMode mode,
            String runId,
            AtomicLong lastSignalMs
    ) {
        String worker = Thread.currentThread().getName();
        long startedAt = System.currentTimeMillis();
        boolean loadedAtStart = false;
        try {
            loadedAtStart = world.isChunkLoaded(task.chunkX(), task.chunkZ());
        } catch (Throwable ignored) {
        }

        RegenActiveTask activeTask = new RegenActiveTask(task.chunkX(), task.chunkZ(), task.attempt(), startedAt, loadedAtStart);
        activeTasks.put(worker, activeTask);
        AtomicReference<String> failurePhase = new AtomicReference<>("unknown");
        AtomicInteger overlayAppliedBlocks = new AtomicInteger();
        AtomicInteger overlayObjectKeys = new AtomicInteger();
        ChunkReplacementListener listener = new ChunkReplacementListener() {
            @Override
            public void onPhase(String phase, int chunkX, int chunkZ, long timestampMs) {
                activeTask.updatePhase(phase, timestampMs);
                lastSignalMs.set(timestampMs);
            }

            @Override
            public void onOverlay(int chunkX, int chunkZ, int appliedBlocks, int objectKeys, long timestampMs) {
                overlayAppliedBlocks.addAndGet(appliedBlocks);
                overlayObjectKeys.addAndGet(objectKeys);
                activeTask.updatePhase("overlay", timestampMs);
                lastSignalMs.set(timestampMs);
            }

            @Override
            public void onFailurePhase(String phase, int chunkX, int chunkZ, Throwable error, long timestampMs) {
                String classifiedPhase = classifyRegenFailurePhase(phase);
                failurePhase.set(classifiedPhase);
                activeTask.updatePhase(classifiedPhase, timestampMs);
                lastSignalMs.set(timestampMs);
            }
        };
        try {
            if (mode.logChunkDiagnostics()) {
                Iris.info("Regen chunk start: id=" + runId
                        + " chunk=" + task.chunkX() + "," + task.chunkZ()
                        + " attempt=" + task.attempt()
                        + " loadedAtStart=" + loadedAtStart
                        + " worker=" + worker);
            }
            ChunkReplacementOptions options = mode == RegenMode.FULL
                    ? ChunkReplacementOptions.full(runId, mode.logChunkDiagnostics())
                    : ChunkReplacementOptions.terrain(runId, mode.logChunkDiagnostics());
            RegenRuntime.setRunId(runId);
            try {
                platform.injectChunkReplacement(world, task.chunkX(), task.chunkZ(), executor, options, listener);
            } finally {
                RegenRuntime.clear();
            }
            if (mode.logChunkDiagnostics()) {
                Iris.info("Regen chunk end: id=" + runId
                        + " chunk=" + task.chunkX() + "," + task.chunkZ()
                        + " attempt=" + task.attempt()
                        + " worker=" + worker
                        + " durationMs=" + (System.currentTimeMillis() - startedAt));
            }
            long finishedAt = System.currentTimeMillis();
            activeTask.updateHeartbeat(finishedAt);
            lastSignalMs.set(finishedAt);
            return RegenChunkResult.success(task, worker, startedAt, finishedAt, loadedAtStart, overlayAppliedBlocks.get(), overlayObjectKeys.get());
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long finishedAt = System.currentTimeMillis();
            String classifiedPhase = classifyRegenFailurePhase(failurePhase.get());
            if ("unknown".equals(classifiedPhase)) {
                classifiedPhase = classifyRegenFailurePhase(activeTask.phase());
            }
            activeTask.updatePhase(classifiedPhase, finishedAt);
            activeTask.updateHeartbeat(finishedAt);
            lastSignalMs.set(finishedAt);
            return RegenChunkResult.failure(
                    task,
                    worker,
                    startedAt,
                    finishedAt,
                    loadedAtStart,
                    classifiedPhase,
                    overlayAppliedBlocks.get(),
                    overlayObjectKeys.get(),
                    e
            );
        } finally {
            activeTasks.remove(worker);
        }
    }

    private Thread createRegenSetupWatchdog(
            World world,
            String runId,
            AtomicBoolean setupDone,
            AtomicReference<String> setupPhase,
            AtomicLong setupPhaseSince
    ) {
        String setupWatchdogName = "Iris-Regen-SetupWatchdog-" + runId;
        Thread setupWatchdog = new Thread(() -> {
            while (!setupDone.get()) {
                try {
                    Thread.sleep(REGEN_HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (!setupDone.get()) {
                    long elapsed = System.currentTimeMillis() - setupPhaseSince.get();
                    Iris.warn("Regen setup heartbeat: id=" + runId
                            + " phase=" + setupPhase.get()
                            + " elapsedMs=" + elapsed
                            + " world=" + world.getName());
                }
            }
        }, setupWatchdogName);
        setupWatchdog.setDaemon(true);
        return setupWatchdog;
    }

    private void setRegenSetupPhase(
            AtomicReference<String> setupPhase,
            AtomicLong setupPhaseSince,
            String nextPhase,
            World world,
            String runId
    ) {
        setupPhase.set(nextPhase);
        setupPhaseSince.set(System.currentTimeMillis());
        if (IrisSettings.get().getGeneral().isDebug()) {
            Iris.info("Regen setup phase: id=" + runId + " phase=" + nextPhase + " world=" + world.getName());
        }
    }

    private static String formatFailedChunkPreview(List<Position2> failedChunks) {
        if (failedChunks.isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder("[");
        int index = 0;
        for (Position2 chunk : failedChunks) {
            if (index > 0) {
                builder.append(", ");
            }
            if (index >= 10) {
                builder.append("...");
                break;
            }
            builder.append(chunk.getX()).append(",").append(chunk.getZ());
            index++;
        }
        builder.append("]");
        return builder.toString();
    }

    private static String summarizeActivePhases(ConcurrentMap<String, RegenActiveTask> activeTasks) {
        if (activeTasks.isEmpty()) {
            return "idle";
        }

        Map<String, Integer> counts = new HashMap<>();
        for (RegenActiveTask activeTask : activeTasks.values()) {
            String phase = classifyRegenFailurePhase(activeTask.phase());
            counts.merge(phase, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "idle";
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int diff = Integer.compare(b.getValue(), a.getValue());
            if (diff != 0) {
                return diff;
            }
            return a.getKey().compareTo(b.getKey());
        });

        StringBuilder builder = new StringBuilder();
        int emitted = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            if (emitted > 0) {
                builder.append(", ");
            }
            if (emitted >= 3) {
                builder.append("...");
                break;
            }
            builder.append(entry.getKey()).append(" x").append(entry.getValue());
            emitted++;
        }
        return builder.toString();
    }

    private static String formatFailurePhaseSummary(Map<String, Integer> failurePhaseCounts) {
        if (failurePhaseCounts.isEmpty()) {
            return "none";
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(failurePhaseCounts.entrySet());
        entries.sort((a, b) -> {
            int diff = Integer.compare(b.getValue(), a.getValue());
            if (diff != 0) {
                return diff;
            }
            return a.getKey().compareTo(b.getKey());
        });

        StringBuilder builder = new StringBuilder();
        int emitted = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            if (emitted > 0) {
                builder.append(", ");
            }
            if (emitted >= 5) {
                builder.append("...");
                break;
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
            emitted++;
        }
        return builder.toString();
    }

    private static String classifyRegenFailurePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }

        String normalized = phase.toLowerCase(Locale.ROOT);
        if (normalized.contains("generate")) {
            return "generate";
        }
        if (normalized.contains("acquire-load-lock") || normalized.contains("reset-mantle")) {
            return "generate";
        }
        if (normalized.contains("apply-terrain") || normalized.contains("folia-region-run")) {
            return "apply-terrain";
        }
        if (normalized.contains("paperlib-async-load") || normalized.contains("folia-run-region")) {
            return "apply-terrain";
        }
        if (normalized.contains("overlay")) {
            return "overlay";
        }
        if (normalized.contains("structure")) {
            return "structures";
        }
        if (normalized.contains("chunk-load-callback")) {
            return "chunk-load-callback";
        }
        return "unknown";
    }

    private static String formatActiveTasks(ConcurrentMap<String, RegenActiveTask> activeTasks) {
        if (activeTasks.isEmpty()) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");
        int count = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RegenActiveTask> entry : activeTasks.entrySet()) {
            if (count > 0) {
                builder.append(", ");
            }
            if (count >= 8) {
                builder.append("...");
                break;
            }
            RegenActiveTask activeTask = entry.getValue();
            builder.append(entry.getKey())
                    .append("=")
                    .append(activeTask.chunkX())
                    .append(",")
                    .append(activeTask.chunkZ())
                    .append("@")
                    .append(activeTask.attempt())
                    .append("/")
                    .append(now - activeTask.startedAtMs())
                    .append("ms")
                    .append(":")
                    .append(classifyRegenFailurePhase(activeTask.phase()))
                    .append("/")
                    .append(now - activeTask.lastHeartbeatMs())
                    .append("ms")
                    .append(activeTask.loadedAtStart() ? ":loaded" : ":cold");
            count++;
        }
        builder.append("}");
        return builder.toString();
    }

    private static void dumpRegenWorkerStacks(Set<Thread> explicitThreads, String worldName) {
        Set<Thread> threads = new LinkedHashSet<>();
        threads.addAll(explicitThreads);
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == null || !thread.isAlive()) {
                continue;
            }

            String name = thread.getName();
            if (name.startsWith("Iris-Regen-")
                    || name.startsWith("Iris EngineSVC-")
                    || name.startsWith("Iris World Manager")
                    || name.contains(worldName)) {
                threads.add(thread);
            }
        }

        for (Thread thread : threads) {
            if (thread == null || !thread.isAlive()) {
                continue;
            }

            Iris.warn("Regen worker thread=" + thread.getName() + " state=" + thread.getState());
            StackTraceElement[] trace = thread.getStackTrace();
            int limit = Math.min(trace.length, REGEN_STACK_LIMIT);
            for (int i = 0; i < limit; i++) {
                Iris.warn("  at " + trace[i]);
            }
        }
    }

    private static final class RegenDisplay {
        private final VolmitSender sender;
        private final BossBar bossBar;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile String title = "";
        private volatile String actionLine = "";
        private volatile double progress = 0.0D;
        private volatile BarColor color = BarColor.BLUE;

        private RegenDisplay(VolmitSender sender, BossBar bossBar) {
            this.sender = sender;
            this.bossBar = bossBar;
        }
    }

    private record RegenChunkTask(int chunkX, int chunkZ, int attempt, long queuedAtMs) {
        private RegenChunkTask retry(long now) {
            return new RegenChunkTask(chunkX, chunkZ, attempt + 1, now);
        }
    }

    private enum RegenMode {
        TERRAIN("terrain", true, false, false, false),
        FULL("full", true, false, true, true);

        private final String id;
        private final boolean usesMaintenance;
        private final boolean bypassMantleStages;
        private final boolean fullMode;
        private final boolean logChunkDiagnostics;

        RegenMode(
                String id,
                boolean usesMaintenance,
                boolean bypassMantleStages,
                boolean fullMode,
                boolean logChunkDiagnostics
        ) {
            this.id = id;
            this.usesMaintenance = usesMaintenance;
            this.bypassMantleStages = bypassMantleStages;
            this.fullMode = fullMode;
            this.logChunkDiagnostics = logChunkDiagnostics;
        }

        private String id() {
            return id;
        }

        private boolean usesMaintenance() {
            return usesMaintenance;
        }

        private boolean bypassMantleStages() {
            return bypassMantleStages;
        }

        private boolean isFullMode() {
            return fullMode;
        }

        private int passCount() {
            return 1;
        }

        private boolean logChunkDiagnostics() {
            return logChunkDiagnostics && IrisSettings.get().getGeneral().isDebug();
        }

        private static RegenMode parse(String raw) {
            if (raw == null) {
                return FULL;
            }

            String normalized = raw.trim();
            if (normalized.isEmpty()) {
                return FULL;
            }

            for (RegenMode mode : values()) {
                if (mode.id.equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }

    private static final class RegenActiveTask {
        private final int chunkX;
        private final int chunkZ;
        private final int attempt;
        private final long startedAtMs;
        private final boolean loadedAtStart;
        private volatile String phase;
        private volatile long lastHeartbeatMs;

        private RegenActiveTask(int chunkX, int chunkZ, int attempt, long startedAtMs, boolean loadedAtStart) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.attempt = attempt;
            this.startedAtMs = startedAtMs;
            this.loadedAtStart = loadedAtStart;
            this.phase = "queued";
            this.lastHeartbeatMs = startedAtMs;
        }

        private void updatePhase(String nextPhase, long timestampMs) {
            this.phase = nextPhase == null || nextPhase.isBlank() ? "unknown" : nextPhase;
            this.lastHeartbeatMs = timestampMs;
        }

        private void updateHeartbeat(long timestampMs) {
            this.lastHeartbeatMs = timestampMs;
        }

        private int chunkX() {
            return chunkX;
        }

        private int chunkZ() {
            return chunkZ;
        }

        private int attempt() {
            return attempt;
        }

        private long startedAtMs() {
            return startedAtMs;
        }

        private boolean loadedAtStart() {
            return loadedAtStart;
        }

        private String phase() {
            return phase;
        }

        private long lastHeartbeatMs() {
            return lastHeartbeatMs;
        }
    }

    private record RegenChunkResult(
            RegenChunkTask task,
            String worker,
            long startedAtMs,
            long finishedAtMs,
            boolean loadedAtStart,
            String failurePhase,
            int overlayAppliedBlocks,
            int overlayObjectKeys,
            boolean success,
            Throwable error
    ) {
        private static RegenChunkResult success(
                RegenChunkTask task,
                String worker,
                long startedAtMs,
                long finishedAtMs,
                boolean loadedAtStart,
                int overlayAppliedBlocks,
                int overlayObjectKeys
        ) {
            return new RegenChunkResult(
                    task,
                    worker,
                    startedAtMs,
                    finishedAtMs,
                    loadedAtStart,
                    "none",
                    overlayAppliedBlocks,
                    overlayObjectKeys,
                    true,
                    null
            );
        }

        private static RegenChunkResult failure(
                RegenChunkTask task,
                String worker,
                long startedAtMs,
                long finishedAtMs,
                boolean loadedAtStart,
                String failurePhase,
                int overlayAppliedBlocks,
                int overlayObjectKeys,
                Throwable error
        ) {
            return new RegenChunkResult(
                    task,
                    worker,
                    startedAtMs,
                    finishedAtMs,
                    loadedAtStart,
                    failurePhase == null || failurePhase.isBlank() ? "unknown" : failurePhase,
                    overlayAppliedBlocks,
                    overlayObjectKeys,
                    false,
                    error
            );
        }

        private String errorSummary() {
            if (error == null) {
                return "unknown";
            }
            String message = error.getMessage();
            if (message == null || message.isEmpty()) {
                return error.getClass().getSimpleName();
            }
            return error.getClass().getSimpleName() + ": " + message;
        }
    }

    private record RegenSummary(
            int totalChunks,
            int successChunks,
            int failedChunks,
            int retryCount,
            String failedPreview,
            String failurePhaseSummary
    ) {
    }
}
