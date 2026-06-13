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

import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.LogLevel;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.spi.PlatformCapabilities;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.spi.PlatformStructureHooks;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.util.function.Consumer;

public final class ModdedPlatform implements IrisPlatform {
    private static volatile Consumer<Throwable> ERROR_SINK = null;

    private final ModdedLoader loader;
    private final ModdedRegistries registries;
    private final ModdedScheduler scheduler;
    private final ModdedCapabilities capabilities;
    private final ModdedStructureHooks structureHooks;
    private final ModdedBiomeWriter biomeWriter;

    public ModdedPlatform(ModdedLoader loader) {
        this.loader = loader;
        this.registries = new ModdedRegistries(loader::currentServer);
        this.scheduler = new ModdedScheduler();
        this.capabilities = new ModdedCapabilities();
        this.structureHooks = new ModdedStructureHooks(loader::currentServer);
        this.biomeWriter = new ModdedBiomeWriter(loader::currentServer);
    }

    public static void errorSink(Consumer<Throwable> sink) {
        ERROR_SINK = sink;
    }

    public MinecraftServer server() {
        return loader.currentServer();
    }

    @Override
    public String platformName() {
        return loader.platformName();
    }

    @Override
    public String minecraftVersion() {
        return loader.minecraftVersion();
    }

    @Override
    public PlatformRegistries registries() {
        return registries;
    }

    @Override
    public PlatformScheduler scheduler() {
        return scheduler;
    }

    @Override
    public PlatformCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public PlatformStructureHooks structureHooks() {
        return structureHooks;
    }

    @Override
    public PlatformBiomeWriter biomeWriter() {
        return biomeWriter;
    }

    @Override
    public File dataFolder() {
        File folder = loader.configDir().resolve("iris").toFile();
        folder.mkdirs();
        return folder;
    }

    @Override
    public File dataFile(String... path) {
        File file = new File(dataFolder(), String.join(File.separator, path));
        file.getParentFile().mkdirs();
        return file;
    }

    @Override
    public File pluginJar() {
        File jar = loader.modJar();
        return jar != null ? jar : new File(dataFolder(), "iris-" + loader.platformName() + ".jar");
    }

    @Override
    public int irisVersionNumber() {
        return 0;
    }

    @Override
    public int minecraftVersionNumber() {
        return 0;
    }

    @Override
    public void callEvent(Object event) {
    }

    @Override
    public void dispatchConsoleCommand(String command) {
    }

    @Override
    public void log(LogLevel level, String message) {
        ModdedIrisLog.log(level, message);
    }

    @Override
    public void msg(String message) {
        ModdedIrisLog.info(message);
    }

    @Override
    public void reportError(Throwable error) {
        Consumer<Throwable> sink = ERROR_SINK;
        if (sink != null && error != null) {
            sink.accept(error);
            return;
        }
        if (error != null) {
            ModdedIrisLog.error("Iris reported error", error);
        }
    }

    private static final class ModdedCapabilities implements PlatformCapabilities {
        @Override
        public boolean customBiomes() {
            return true;
        }

        @Override
        public boolean dataPacks() {
            return true;
        }

        @Override
        public boolean structurePlacement() {
            return true;
        }

        @Override
        public boolean regionizedThreading() {
            return false;
        }
    }
}
