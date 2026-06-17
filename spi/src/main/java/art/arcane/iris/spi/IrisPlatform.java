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

package art.arcane.iris.spi;

import java.io.File;

/**
 * Root platform service provided by each adapter; the single entry point core uses to reach the host platform.
 */
public interface IrisPlatform {
    String platformName();

    String minecraftVersion();

    PlatformRegistries registries();

    PlatformScheduler scheduler();

    PlatformCapabilities capabilities();

    PlatformStructureHooks structureHooks();

    PlatformBiomeWriter biomeWriter();

    File dataFolder();

    default File dataFolder(String... path) {
        if (path == null || path.length == 0) {
            return dataFolder();
        }

        File folder = new File(dataFolder(), String.join(File.separator, path));
        folder.mkdirs();
        return folder;
    }

    default File dataFolderNoCreate(String... path) {
        if (path == null || path.length == 0) {
            return dataFolder();
        }

        return new File(dataFolder(), String.join(File.separator, path));
    }

    File dataFile(String... path);

    File pluginJar();

    int irisVersionNumber();

    int minecraftVersionNumber();

    void callEvent(Object event);

    void dispatchConsoleCommand(String command);

    boolean spawnEntity(Object world, String entityKey, double x, double y, double z);

    boolean giveItem(Object player, String itemKey, int amount);

    void log(LogLevel level, String message);

    void msg(String message);

    void reportError(Throwable error);
}
