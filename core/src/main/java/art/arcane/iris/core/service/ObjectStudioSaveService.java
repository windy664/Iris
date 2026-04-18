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

package art.arcane.iris.core.service;

import art.arcane.iris.Iris;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.runtime.ObjectStudioActivation;
import art.arcane.iris.core.runtime.ObjectStudioLayout;
import art.arcane.iris.core.runtime.ObjectStudioLayout.GridCell;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.platform.studio.generators.ObjectStudioGenerator;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ObjectStudioSaveService implements IrisService {
    public static final int INTERVAL_TICKS = 100;
    private static final int CELLS_PER_PASS = 50;

    private static ObjectStudioSaveService INSTANCE;

    private final Map<UUID, ActiveStudio> studios = new ConcurrentHashMap<>();
    private int taskId = -1;

    public static ObjectStudioSaveService get() {
        ObjectStudioSaveService svc = INSTANCE;
        if (svc != null) return svc;
        svc = Iris.service(ObjectStudioSaveService.class);
        return svc;
    }

    @Override
    public void onEnable() {
        INSTANCE = this;
        taskId = J.ar(this::pass, INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            J.car(taskId);
            taskId = -1;
        }
        studios.clear();
        INSTANCE = null;
    }

    public void register(Engine engine, ObjectStudioGenerator generator) {
        World world = engine.getTarget().getWorld().realWorld();
        if (world == null) return;
        ObjectStudioLayout layout = generator.getLayout();
        if (layout == null) return;

        Map<String, IrisData> sources = generator.getPackData();
        if (sources == null || sources.isEmpty()) {
            Iris.warn("Object Studio save disabled: no pack data sources available for world %s", world.getName());
            return;
        }

        Map<String, File> objectsDirs = new ConcurrentHashMap<>();
        for (Map.Entry<String, IrisData> e : sources.entrySet()) {
            File dir = resolveObjectsDir(e.getValue());
            if (dir != null) {
                objectsDirs.put(e.getKey(), dir);
            }
        }
        if (objectsDirs.isEmpty()) {
            Iris.warn("Object Studio save disabled: no resolvable objects folders for world %s", world.getName());
            return;
        }

        ActiveStudio existing = studios.get(world.getUID());
        if (existing != null && existing.layout == layout) {
            return;
        }

        String packKey = engine.getDimension() == null ? null : engine.getDimension().getLoadKey();
        studios.put(world.getUID(), new ActiveStudio(world.getUID(), layout, objectsDirs, packKey));
        Iris.info("Object Studio live-save registered: world=%s cells=%d packs=%d",
                world.getName(), layout.cells().size(), objectsDirs.size());
    }

    public void unregister(World world) {
        if (world == null) return;
        ActiveStudio removed = studios.remove(world.getUID());
        if (removed != null) {
            if (removed.packKey != null) {
                ObjectStudioActivation.deactivate(removed.packKey);
            }
            Iris.info("Object Studio live-save unregistered: world=%s", world.getName());
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        unregister(event.getWorld());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!studios.containsKey(event.getLocation().getWorld().getUID())) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event instanceof CreatureSpawnEvent) return;
        if (!studios.containsKey(event.getLocation().getWorld().getUID())) return;
        if (event.getEntity() instanceof org.bukkit.entity.Player) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        World world = clicked.getWorld();
        ActiveStudio studio = studios.get(world.getUID());
        if (studio == null) return;

        GridCell cell = studio.layout.findAt(clicked.getX(), clicked.getZ());
        if (cell == null) return;

        Player player = event.getPlayer();
        Iris.info("Object Studio save triggered by %s for %s", player.getName(), cell.key());
        J.runRegion(world, cell.chunkMinX(), cell.chunkMinZ(), () -> {
            try {
                captureAndSave(studio, world, cell);
            } catch (Throwable e) {
                Iris.reportError(e);
            }
        });
    }

    public boolean teleportTo(Player player, String objectKey) {
        if (player == null || objectKey == null) return false;
        for (ActiveStudio studio : studios.values()) {
            GridCell cell = studio.layout.get(objectKey);
            if (cell == null) continue;
            World world = Bukkit.getWorld(studio.worldId);
            if (world == null) continue;

            double targetX = cell.originX() + cell.w() / 2.0D + 0.5D;
            double targetZ = cell.originZ() + cell.d() / 2.0D + 0.5D;
            double targetY = cell.originY() + cell.h() + 2.0D;
            Location location = new Location(world, targetX, targetY, targetZ);
            J.runEntity(player, () -> PaperLib.teleportAsync(player, location));
            Iris.info("Object Studio goto: %s -> %s at %.0f,%.0f,%.0f",
                    player.getName(), objectKey, location.getX(), location.getY(), location.getZ());
            return true;
        }
        return false;
    }

    private static File resolveObjectsDir(IrisData data) {
        File root = data.getDataFolder();
        if (root == null) return null;
        File objects = new File(root, "objects");
        if (!objects.exists()) {
            objects.mkdirs();
        }
        return objects;
    }

    private void pass() {
        if (studios.isEmpty()) return;

        for (ActiveStudio studio : studios.values()) {
            World world = Bukkit.getWorld(studio.worldId);
            if (world == null) continue;

            int budget = CELLS_PER_PASS;
            int size = studio.layout.cells().size();
            if (size == 0) continue;

            while (budget-- > 0) {
                int idx = studio.cursor.getAndIncrement();
                if (idx >= size) {
                    studio.cursor.set(0);
                    idx = 0;
                }
                GridCell cell = studio.layout.cells().get(idx);
                scheduleCapture(studio, world, cell);
                if (size <= CELLS_PER_PASS && idx == size - 1) break;
            }
        }
    }

    private void scheduleCapture(ActiveStudio studio, World world, GridCell cell) {
        int chunkX = cell.chunkMinX();
        int chunkZ = cell.chunkMinZ();
        J.runRegion(world, chunkX, chunkZ, () -> {
            try {
                captureAndSave(studio, world, cell);
            } catch (Throwable e) {
                Iris.reportError(e);
            }
        });
    }

    private void captureAndSave(ActiveStudio studio, World world, GridCell cell) {
        if (!allChunksLoaded(world, cell)) {
            return;
        }

        IrisObject snapshot = new IrisObject(cell.w(), cell.h(), cell.d());
        int originX = cell.originX();
        int originY = cell.originY();
        int originZ = cell.originZ();

        boolean anyBlock = false;
        for (int dx = 0; dx < cell.w(); dx++) {
            for (int dy = 0; dy < cell.h(); dy++) {
                for (int dz = 0; dz < cell.d(); dz++) {
                    Block block = world.getBlockAt(originX + dx, originY + dy, originZ + dz);
                    if (block.getType() == Material.AIR) continue;
                    snapshot.setUnsigned(dx, dy, dz, block, false);
                    anyBlock = true;
                }
            }
        }

        String hashKey = cell.pack() + "/" + cell.key();
        long hash = hashOf(snapshot);
        Long prior = studio.hashes.get(hashKey);
        if (prior != null && prior == hash) {
            return;
        }

        if (!anyBlock && prior == null) {
            studio.hashes.put(hashKey, hash);
            return;
        }

        studio.hashes.put(hashKey, hash);

        File targetFile = objectFileFor(studio, cell);
        if (targetFile == null) return;

        J.a(() -> {
            try {
                File parent = targetFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                snapshot.write(targetFile);
                Iris.info("Object Studio saved: %s/%s (%dx%dx%d)",
                        cell.pack(), cell.key(), cell.w(), cell.h(), cell.d());
            } catch (Throwable e) {
                Iris.reportError(e);
            }
        });
    }

    private boolean allChunksLoaded(World world, GridCell cell) {
        for (int cx = cell.chunkMinX(); cx <= cell.chunkMaxX(); cx++) {
            for (int cz = cell.chunkMinZ(); cz <= cell.chunkMaxZ(); cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long hashOf(IrisObject snapshot) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            snapshot.write(baos);
            byte[] bytes = baos.toByteArray();
            long h = 1125899906842597L;
            for (byte b : bytes) {
                h = 31 * h + b;
            }
            return h;
        } catch (Throwable e) {
            Iris.reportError(e);
            return System.nanoTime();
        }
    }

    private static File objectFileFor(ActiveStudio studio, GridCell cell) {
        File objectsDir = studio.objectsDirs.get(cell.pack());
        if (objectsDir == null) return null;
        String relative = cell.key().replace('\\', '/');
        return new File(objectsDir, relative + ".iob");
    }

    private static final class ActiveStudio {
        final UUID worldId;
        final ObjectStudioLayout layout;
        final Map<String, File> objectsDirs;
        final String packKey;
        final Map<String, Long> hashes = new ConcurrentHashMap<>();
        final AtomicInteger cursor = new AtomicInteger();

        ActiveStudio(UUID worldId, ObjectStudioLayout layout, Map<String, File> objectsDirs, String packKey) {
            this.worldId = worldId;
            this.layout = layout;
            this.objectsDirs = objectsDirs;
            this.packKey = packKey;
        }
    }
}
