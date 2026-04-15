package art.arcane.iris.core.nms;

import org.bukkit.Server;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MinecraftVersion {
    private static final Pattern DECORATED_VERSION_PATTERN = Pattern.compile("\\(MC: ([0-9]+(?:\\.[0-9]+){0,2})\\)");

    private final String value;
    private final int major;
    private final int minor;

    private MinecraftVersion(String value, int major, int minor) {
        this.value = value;
        this.major = major;
        this.minor = minor;
    }

    public static MinecraftVersion detect(Server server) {
        if (server == null) {
            return null;
        }

        MinecraftVersion runtimeVersion = fromRuntimeMinecraftVersion(server);
        if (runtimeVersion != null) {
            return runtimeVersion;
        }

        MinecraftVersion decoratedVersion = fromDecoratedVersion(server.getVersion());
        if (decoratedVersion != null) {
            return decoratedVersion;
        }

        return fromBukkitVersion(server.getBukkitVersion());
    }

    static MinecraftVersion fromRuntimeMinecraftVersion(Server server) {
        try {
            Method method = server.getClass().getMethod("getMinecraftVersion");
            Object value = method.invoke(server);
            if (value instanceof String version) {
                return fromVersionToken(version);
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }

        return null;
    }

    static MinecraftVersion fromDecoratedVersion(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        Matcher matcher = DECORATED_VERSION_PATTERN.matcher(input);
        if (!matcher.find()) {
            return null;
        }

        return fromVersionToken(matcher.group(1));
    }

    static MinecraftVersion fromBukkitVersion(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String versionToken = input.split("-", 2)[0].trim();
        return fromVersionToken(versionToken);
    }

    private static MinecraftVersion fromVersionToken(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String[] parts = input.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            return null;
        }

        try {
            int major = Integer.parseInt(parts[1]);
            int minor = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new MinecraftVersion(input, major, minor);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public String value() {
        return value;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public boolean isAtLeast(int major, int minor) {
        return this.major > major || (this.major == major && this.minor >= minor);
    }

    public boolean isNewerThan(int major, int minor) {
        return this.major > major || (this.major == major && this.minor > minor);
    }
}
