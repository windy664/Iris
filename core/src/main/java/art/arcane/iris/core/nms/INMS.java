/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.nms;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.nms.v1X.NMSBinding1X;
import org.bukkit.Bukkit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class INMS {
    private static final Version CURRENT = new Version(26, 1, 2, "v26_1_R1");

    private static final List<Version> REVISION = List.of(
            CURRENT
    );

    //@done
    private static final INMSBinding binding = bind();

    public static INMSBinding get() {
        return binding;
    }

    public static String getNMSTag() {
        if (IrisSettings.get().getGeneral().isDisableNMS()) {
            return "BUKKIT";
        }

        try {
            String name = Bukkit.getServer().getClass().getCanonicalName();
            if (name.equals("org.bukkit.craftbukkit.CraftServer")) {
                return getTag(REVISION, "BUKKIT");
            } else {
                return name.split("\\Q.\\E")[3];
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.error("Failed to determine server nms version!");
            e.printStackTrace();
        }

        return "BUKKIT";
    }

    private static INMSBinding bind() {
        String code = getNMSTag();
        boolean disableNms = IrisSettings.get().getGeneral().isDisableNMS();
        List<String> probeCodes = NmsBindingProbeSupport.getBindingProbeCodes(code, disableNms, getFallbackBindingCodes());
        if ("BUKKIT".equals(code) && !disableNms) {
            IrisLogging.info("NMS tag resolution fell back to Bukkit; probing supported revision bindings.");
        }

        for (int i = 0; i < probeCodes.size(); i++) {
            INMSBinding resolvedBinding = tryBind(probeCodes.get(i), i == 0);
            if (resolvedBinding != null) {
                return resolvedBinding;
            }
        }

        if (disableNms) {
            IrisLogging.info("Craftbukkit " + code + " <-> " + NMSBinding1X.class.getSimpleName() + " Successfully Bound");
            IrisLogging.warn("Note: NMS support is disabled. Iris is running in limited Bukkit fallback mode.");
            return new NMSBinding1X();
        }

        MinecraftVersion detectedVersion = getMinecraftVersion();
        String serverVersion = detectedVersion == null ? Bukkit.getServer().getVersion() : detectedVersion.value();
        throw new IllegalStateException("Iris requires Minecraft 26.1.2. Detected server version: " + serverVersion);
    }

    private static String getTag(List<Version> versions, String def) {
        MinecraftVersion detectedVersion = getMinecraftVersion();
        if (detectedVersion == null) {
            return def;
        }

        for (Version p : versions) {
            if (!detectedVersion.isSameRelease(p.major, p.minor, p.patch)) {
                continue;
            }
            return p.tag;
        }
        return def;
    }

    private static MinecraftVersion getMinecraftVersion() {
        try {
            return MinecraftVersion.detect(Bukkit.getServer());
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.error("Failed to determine server minecraft version!");
            e.printStackTrace();
            return null;
        }
    }

    private static INMSBinding tryBind(String code, boolean announce) {
        if (announce) {
            IrisLogging.info("Locating NMS Binding for " + code);
        } else {
            IrisLogging.info("Probing NMS Binding for " + code);
        }

        try {
            Class<?> clazz = Class.forName("art.arcane.iris.core.nms." + code + ".NMSBinding");
            Object candidate = clazz.getConstructor().newInstance();
            if (candidate instanceof INMSBinding binding) {
                IrisLogging.info("Craftbukkit " + code + " <-> " + candidate.getClass().getSimpleName() + " Successfully Bound");
                return binding;
            }
        } catch (ClassNotFoundException | NoClassDefFoundError classNotFoundException) {
            IrisLogging.warn("Failed to load NMS binding class for " + code + ": " + classNotFoundException.getMessage());
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
        }

        return null;
    }

    private static Set<String> getFallbackBindingCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (Version version : REVISION) {
            if (version.tag != null && !version.tag.isBlank()) {
                codes.add(version.tag);
            }
        }
        return codes;
    }

    private record Version(int major, int minor, int patch, String tag) {}
}
