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

import java.util.concurrent.ConcurrentHashMap;

public final class IrisServices {
    private static final ConcurrentHashMap<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();

    private IrisServices() {
    }

    public static void register(Class<?> type, Object implementation) {
        SERVICES.put(type, type.cast(implementation));
    }

    public static <T> T get(Class<T> type) {
        Object implementation = SERVICES.get(type);
        if (implementation == null) {
            throw new IllegalStateException("No Iris service registered for " + type.getName());
        }
        return type.cast(implementation);
    }

    public static void clear() {
        SERVICES.clear();
    }
}
