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
import java.util.regex.Pattern;

public final class IrisLogging {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)\\u00a7[0-9A-FK-ORX]");
    private static final Pattern MINI_MESSAGE_TAG = Pattern.compile("(?i)</?(?:reset|bold|b|italic|i|underlined?|u|strikethrough|st|obfuscated?|obf|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|gradient|font|hover|click|rainbow)(?::[^>\\n]{0,96})?>|<#[0-9a-f]{6}>");

    private IrisLogging() {
    }

    public static void info(String format, Object... args) {
        emit(LogLevel.INFO, format(format, args));
    }

    public static void debug(String message) {
        emit(LogLevel.DEBUG, message);
    }

    public static void warn(String format, Object... args) {
        emit(LogLevel.WARN, format(format, args));
    }

    public static void error(String format, Object... args) {
        emit(LogLevel.ERROR, format(format, args));
    }

    public static void msg(String message) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().msg(message);
            return;
        }

        System.out.println("[Iris] " + clean(message));
    }

    public static void reportError(String context, Throwable error) {
        Throwable cause = error == null ? new IllegalStateException("Unknown Iris failure") : error;
        String message = context == null || context.isBlank() ? "Unhandled Iris failure." : context;

        try {
            error(message);
        } catch (Throwable inner) {
            System.err.println("[Iris] " + message);
            inner.printStackTrace(System.err);
        }

        reportError(cause);
        cause.printStackTrace(System.err);
    }

    public static void reportError(Throwable error) {
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().reportError(error);
            return;
        }

        if (error != null) {
            System.err.println("[Iris/ERROR] " + error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            error.printStackTrace(System.err);
        }
    }

    public static String format(String format, Object... args) {
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

    public static String clean(String message) {
        if (message == null) {
            return "null";
        }

        String legacyStripped = LEGACY_COLOR.matcher(message).replaceAll("");
        return MINI_MESSAGE_TAG.matcher(legacyStripped).replaceAll("");
    }

    private static void emit(LogLevel level, String message) {
        LogLevel target = level == null ? LogLevel.INFO : level;
        if (IrisPlatforms.isBound()) {
            IrisPlatforms.get().log(target, message);
            return;
        }

        String output = clean(message);
        if (target == LogLevel.WARN || target == LogLevel.ERROR) {
            System.err.println("[Iris/" + target + "] " + output);
            return;
        }

        System.out.println("[Iris/" + target + "] " + output);
    }
}
