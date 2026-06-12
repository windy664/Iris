/*
 * Iris is a World Generator for Minecraft Servers
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

package art.arcane.iris.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class ClassloadProbe {
    private static final String[] CRITICAL_PREFIXES = {
            "art.arcane.iris.engine.",
            "art.arcane.iris.util.",
            "art.arcane.iris.core.loader.",
            "art.arcane.iris.spi.",
    };

    private static final String[] EXEMPT_PREFIXES = {
            "art.arcane.iris.platform.bukkit.",
            "art.arcane.iris.engine.platform.",
            "art.arcane.iris.util.common.plugin.",
            "art.arcane.iris.util.common.inventorygui.",
            "art.arcane.iris.util.common.director.",
            "art.arcane.iris.util.common.data.registry.",
    };

    private static final String[] EXEMPT_CLASSES = {
            "art.arcane.iris.util.common.misc.Bindings",
            "art.arcane.iris.util.common.misc.SlimJar",
            "art.arcane.iris.util.common.misc.ServerProperties",
            "art.arcane.iris.util.common.data.IrisCustomData",
            "art.arcane.iris.engine.IrisWorldManager",
            "art.arcane.iris.engine.framework.EngineAssignedWorldManager",
            "art.arcane.iris.engine.object.StudioMode",
            "art.arcane.iris.engine.framework.placer.WorldObjectPlacer",
    };

    public static void main(String[] args) throws IOException {
        art.arcane.iris.spi.IrisPlatforms.bind(new StubPlatform());
        boolean bukkitPresent = true;
        try {
            Class.forName("org.bukkit.Bukkit", false, ClassloadProbe.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            bukkitPresent = false;
        }
        System.out.println("[probe] org.bukkit on classpath: " + bukkitPresent);

        Path classesRoot = Path.of(args[0]);
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String rel = classesRoot.relativize(p).toString();
                        String name = rel.substring(0, rel.length() - 6).replace('/', '.');
                        if (name.contains("$")) {
                            return;
                        }
                        names.add(name);
                    });
        }
        names.sort(String::compareTo);

        TreeMap<String, String> criticalFailures = new TreeMap<>();
        TreeMap<String, String> otherFailures = new TreeMap<>();
        int loaded = 0;
        int criticalTotal = 0;
        for (String name : names) {
            boolean critical = matchesAny(name, CRITICAL_PREFIXES) && !matchesAny(name, EXEMPT_PREFIXES) && !exactAny(name);
            if (critical) {
                criticalTotal++;
            }
            try {
                Class.forName(name, true, ClassloadProbe.class.getClassLoader());
                loaded++;
            } catch (Throwable failure) {
                if (System.getenv("PROBE_TRACE") != null && name.contains(System.getenv("PROBE_TRACE"))) {
                    failure.printStackTrace(System.out);
                }
                String cause = rootCause(failure);
                if (critical) {
                    criticalFailures.put(name, cause);
                } else {
                    otherFailures.put(name, cause);
                }
            }
        }

        System.out.println("[probe] classes scanned: " + names.size() + ", initialized OK: " + loaded);
        System.out.println("[probe] critical set: " + criticalTotal + ", critical failures: " + criticalFailures.size());
        criticalFailures.forEach((name, cause) -> System.out.println("  CRITICAL " + name + "  ->  " + cause));
        System.out.println("[probe] non-critical failures: " + otherFailures.size());
        otherFailures.forEach((name, cause) -> System.out.println("  other " + name + "  ->  " + cause));

        if (!criticalFailures.isEmpty()) {
            System.out.println("[probe] RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("[probe] RESULT: PASS");
    }

    private static boolean exactAny(String name) {
        for (String exempt : EXEMPT_CLASSES) {
            if (name.equals(exempt)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
