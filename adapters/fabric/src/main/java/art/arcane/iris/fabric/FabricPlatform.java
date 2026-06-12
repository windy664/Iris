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

package art.arcane.iris.fabric;

import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.LogLevel;
import art.arcane.iris.spi.PlatformBiomeWriter;
import art.arcane.iris.spi.PlatformCapabilities;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.spi.PlatformScheduler;
import art.arcane.iris.spi.PlatformStructureHooks;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FabricPlatform implements IrisPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static volatile Consumer<Throwable> ERROR_SINK = null;

    private final Supplier<MinecraftServer> server;
    private final FabricRegistries registries;
    private final FabricScheduler scheduler;
    private final FabricCapabilities capabilities;
    private final FabricStructureHooks structureHooks;
    private final FabricBiomeWriter biomeWriter;

    public FabricPlatform(Supplier<MinecraftServer> server) {
        this.server = server;
        this.registries = new FabricRegistries(server);
        this.scheduler = new FabricScheduler();
        this.capabilities = new FabricCapabilities();
        this.structureHooks = new FabricStructureHooks(server);
        this.biomeWriter = new FabricBiomeWriter();
    }

    public static void errorSink(Consumer<Throwable> sink) {
        ERROR_SINK = sink;
    }

    public MinecraftServer server() {
        return server.get();
    }

    @Override
    public String platformName() {
        return "fabric";
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
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
        File folder = FabricLoader.getInstance().getConfigDir().resolve("iris").toFile();
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
        return FabricLoader.getInstance().getModContainer("irisworldgen")
                .flatMap((ModContainer container) -> container.getOrigin().getPaths().stream().findFirst())
                .map((java.nio.file.Path p) -> p.toFile())
                .orElse(new File(dataFolder(), "iris-fabric.jar"));
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
        switch (level) {
            case DEBUG -> LOGGER.debug(message);
            case INFO -> LOGGER.info(message);
            case WARN -> LOGGER.warn(message);
            case ERROR -> LOGGER.error(message);
        }
    }

    @Override
    public void msg(String message) {
        LOGGER.info(message);
    }

    @Override
    public void reportError(Throwable error) {
        Consumer<Throwable> sink = ERROR_SINK;
        if (sink != null && error != null) {
            sink.accept(error);
            return;
        }
        if (error != null) {
            LOGGER.error("Iris reported error", error);
        }
    }

    private static final class FabricCapabilities implements PlatformCapabilities {
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
