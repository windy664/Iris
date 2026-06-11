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
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.LogLevel;
import art.arcane.iris.spi.PlatformCapabilities;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;

import java.io.File;

/**
 * Bukkit implementation of the root Iris platform service.
 */
public final class BukkitPlatform implements IrisPlatform {
    private final BukkitRegistries registries = new BukkitRegistries();
    private final BukkitScheduler scheduler = new BukkitScheduler();
    private final PlatformCapabilities capabilities = new BukkitCapabilities();

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
    public File dataFolder() {
        return Iris.instance.getDataFolder();
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
