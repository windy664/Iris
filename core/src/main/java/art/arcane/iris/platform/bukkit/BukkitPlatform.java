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

package art.arcane.iris.platform.bukkit;

import art.arcane.iris.Iris;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.LogLevel;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.spi.PlatformCapabilities;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.spi.PlatformStructureHooks;
import art.arcane.iris.spi.PlatformWorld;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.math.Vector3d;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;

/**
 * Bukkit implementation of the root Iris platform service.
 */
public final class BukkitPlatform implements IrisPlatform {
    private final BukkitRegistries registries = new BukkitRegistries();
    private final BukkitScheduler scheduler = new BukkitScheduler();
    private final PlatformCapabilities capabilities = new BukkitCapabilities();
    private final BukkitStructureHooks structureHooks = new BukkitStructureHooks();
    private final BukkitBiomeWriter biomeWriter = new BukkitBiomeWriter();

    public static World unwrapWorld(PlatformWorld world) {
        return (World) world.nativeHandle();
    }

    public static PlatformBiome wrapBiome(Object biome) {
        return BukkitBiome.of((Biome) biome);
    }

    public static Class<?> classifyMantleValue(Object value) {
        if (value instanceof World) {
            return World.class;
        }

        if (value instanceof BlockData) {
            return BlockData.class;
        }

        if (value instanceof Entity) {
            return Entity.class;
        }

        return value.getClass();
    }

    public static void unregisterListener(Object candidate) {
        if (candidate instanceof Listener listener) {
            Iris.instance.unregisterListener(listener);
        }
    }

    public static IrisPosition positionOf(Location location) {
        return new IrisPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static IrisPosition positionOf(Vector vector) {
        return new IrisPosition(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
    }

    public static Location toLocation(IrisPosition position, World world) {
        return new Location(world, position.getX(), position.getY(), position.getZ());
    }

    public static Entity spawnEntity(Location at, EntityType type, CreatureSpawnEvent.SpawnReason reason) {
        return INMS.get().spawnEntity(at, type, reason);
    }

    public static Vector3d entityBoundingBox(EntityType type) {
        return INMS.get().getBoundingbox(type);
    }

    public static boolean hasTile(Material material) {
        return INMS.get().hasTile(material);
    }

    public static KMap<String, Object> serializeTile(Location location) {
        return INMS.get().serializeTile(location);
    }

    public static void deserializeTile(KMap<String, Object> properties, Location location) {
        INMS.get().deserializeTile(properties, location);
    }

    public static ItemStack applyCustomNbt(ItemStack itemStack, KMap<String, Object> customNbt) {
        return INMS.get().applyCustomNbt(itemStack, customNbt);
    }

    public static int dataPackFormat() {
        return INMS.get().getDataVersion().getPackFormat();
    }

    @Override
    public String platformName() {
        return "bukkit";
    }

    @Override
    public String minecraftVersion() {
        return Bukkit.getBukkitVersion();
    }

    @Override
    public PlatformRegistries registries() {
        return registries;
    }

    @Override
    public PlatformScheduler scheduler() {
        return scheduler;
    }

    @Override
    public PlatformCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public PlatformStructureHooks structureHooks() {
        return structureHooks;
    }

    @Override
    public PlatformBiomeWriter biomeWriter() {
        return biomeWriter;
    }

    @Override
    public File dataFolder() {
        return Iris.instance.getDataFolder();
    }

    @Override
    public File dataFile(String... path) {
        return Iris.instance.getDataFile(path);
    }

    @Override
    public File pluginJar() {
        return Iris.instance.getJarFile();
    }

    @Override
    public int irisVersionNumber() {
        return Iris.instance.getIrisVersion();
    }

    @Override
    public int minecraftVersionNumber() {
        return Iris.instance.getMCVersion();
    }

    @Override
    public void callEvent(Object event) {
        Iris.callEvent((Event) event);
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void log(LogLevel level, String message) {
        switch (level) {
            case DEBUG -> Iris.debug(message);
            case INFO -> Iris.info(message);
            case WARN -> Iris.warn(message);
            case ERROR -> Iris.error(message);
        }
    }

    @Override
    public void msg(String message) {
        Iris.msg(message);
    }

    @Override
    public void reportError(Throwable error) {
        Iris.reportError(error);
    }

    private static final class BukkitCapabilities implements PlatformCapabilities {
        @Override
        public boolean customBiomes() {
            return INMS.get().supportsCustomBiomes();
        }

        @Override
        public boolean dataPacks() {
            return INMS.get().supportsDataPacks();
        }

        @Override
        public boolean structurePlacement() {
            return true;
        }

        @Override
        public boolean regionizedThreading() {
            return J.isFolia();
        }
    }
}
