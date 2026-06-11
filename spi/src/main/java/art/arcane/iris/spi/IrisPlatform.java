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

    File dataFolder();

    void dispatchConsoleCommand(String command);

    void log(LogLevel level, String message);
}
