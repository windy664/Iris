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

import java.util.IllegalFormatException;

public final class IrisLogging {
    private IrisLogging() {
    }

    public static void info(String format, Object... args) {
        emit(LogLevel.INFO, safeFormat(format, args));
    }

    public static void debug(String message) {
        emit(LogLevel.DEBUG, message);
    }

    public static void warn(String format, Object... args) {
        emit(LogLevel.WARN, safeFormat(format, args));
    }

    public static void error(String format, Object... args) {
        emit(LogLevel.ERROR, safeFormat(format, args));
    }

    public static void msg(String message) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().msg(message);
            return;
        }

        System.out.println("[Iris] " + message);
    }

    public static void reportError(Throwable error) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().reportError(error);
            return;
        }

        if (error != null) {
            System.out.println("[Iris/ERROR] " + error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            error.printStackTrace(System.out);
        }
    }

    private static void emit(LogLevel level, String message) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().log(level, message);
            return;
        }

        System.out.println("[Iris/" + level + "] " + message);
    }

    private static String safeFormat(String format, Object... args) {
        if (format == null) {
            return "null";
        }

        if (args == null || args.length == 0) {
            return format;
        }

        try {
            return String.format(format, args);
        } catch (IllegalFormatException ignored) {
            return format;
        }
    }
}
