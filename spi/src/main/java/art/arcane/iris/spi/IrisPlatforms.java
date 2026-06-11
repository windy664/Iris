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

/**
 * Static holder binding the active platform adapter for the lifetime of the runtime.
 */
public final class IrisPlatforms {
    private static volatile IrisPlatform platform;

    private IrisPlatforms() {
    }

    public static synchronized void bind(IrisPlatform p) {
        if (platform != null && platform != p) {
            throw new IllegalStateException("Iris platform is already bound to a different instance");
        }
        platform = p;
    }

    public static IrisPlatform get() {
        IrisPlatform bound = platform;
        if (bound == null) {
            throw new IllegalStateException("No Iris platform is bound");
        }
        return bound;
    }

    public static boolean isBound() {
        return platform != null;
    }
}
