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

package art.arcane.iris.modded;

import art.arcane.iris.BuildConstants;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.splash.IrisSplashPackScanner;
import art.arcane.iris.core.splash.IrisSplashPackScanner.SplashPackMetadata;
import art.arcane.iris.core.splash.IrisSplashRenderer;
import art.arcane.iris.spi.IrisLogging;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ModdedIrisSplash {

    private ModdedIrisSplash() {
    }

    public static void print(ModdedLoader loader) {
        printPacks(loader);
        if (isLogoEnabled()) {
            printLogo(loader);
        }
    }

    private static void printPacks(ModdedLoader loader) {
        File packFolder = loader.configDir().resolve("irisworldgen").resolve("packs").toFile();
        List<SplashPackMetadata> packs = IrisSplashPackScanner.collect(packFolder, IrisLogging::reportError);
        if (packs.isEmpty()) {
            return;
        }

        IrisLogging.info("Custom Dimensions: " + packs.size());
        for (SplashPackMetadata pack : packs) {
            IrisLogging.info("  " + pack.name() + " v" + pack.version());
        }
    }

    private static void printLogo(ModdedLoader loader) {
        String padding = " ".repeat(4);
        String version = loader.modVersion();
        String releaseTrain = getReleaseTrain(version);
        String startupDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int javaVersion = getJavaVersion();
        String[] splash = IrisSplashRenderer.renderPlain();
        String[] info = new String[]{
                "",
                " Iris, Dimension Engine [" + releaseTrain + " RC.1.1.6]",
                " Version: " + version,
                " By: Volmit Software (Arcane Arts)",
                " Server: " + loader.platformName() + " / Minecraft " + loader.minecraftVersion(),
                " Java: " + javaVersion + " | Date: " + startupDate,
                " Commit: " + BuildConstants.COMMIT + "/" + BuildConstants.ENVIRONMENT,
                "",
                "",
                "",
                ""
        };

        StringBuilder builder = new StringBuilder("\n\n");
        for (int i = 0; i < splash.length; i++) {
            builder.append(padding).append(splash[i]).append(info[i]).append('\n');
        }

        IrisLogging.info(builder.toString());
    }

    private static boolean isLogoEnabled() {
        try {
            return IrisSettings.get().getGeneral().isSplashLogoStartup();
        } catch (Throwable error) {
            IrisLogging.warn("Iris splash setting could not be read: " + error.getClass().getSimpleName());
            return true;
        }
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf('.');
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    private static String getReleaseTrain(String version) {
        String value = version == null ? "unknown" : version;
        int suffixIndex = value.indexOf('-');
        if (suffixIndex >= 0) {
            value = value.substring(0, suffixIndex);
        }
        String[] split = value.split("\\.");
        if (split.length >= 2) {
            return split[0] + "." + split[1];
        }
        return value;
    }
}
