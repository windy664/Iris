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

import art.arcane.iris.modded.service.ModdedService;
import art.arcane.iris.modded.service.ModdedTickableService;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ModdedServiceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private final Map<Class<? extends ModdedService>, ModdedService> services = new LinkedHashMap<>();
    private boolean enabled = false;

    public synchronized <T extends ModdedService> T register(Class<T> type, T service) {
        if (services.containsKey(type)) {
            return type.cast(services.get(type));
        }
        services.put(type, service);
        if (enabled) {
            enableService(service);
        }
        return service;
    }

    public synchronized <T extends ModdedService> T service(Class<T> type) {
        ModdedService service = services.get(type);
        return service == null ? null : type.cast(service);
    }

    public synchronized void enableAll() {
        if (enabled) {
            return;
        }
        enabled = true;
        for (ModdedService service : services.values()) {
            enableService(service);
        }
    }

    public synchronized void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }
        for (ModdedService service : services.values()) {
            if (service instanceof ModdedTickableService tickable) {
                tickService(tickable, server);
            }
        }
    }

    public synchronized void disableAll() {
        if (!enabled) {
            return;
        }
        enabled = false;
        forEachReversed(this::disableService);
        services.clear();
    }

    private void enableService(ModdedService service) {
        try {
            service.onEnable();
        } catch (Throwable error) {
            LOGGER.error("Iris service onEnable failed for {}", service.getClass().getName(), error);
        }
    }

    private void disableService(ModdedService service) {
        try {
            service.onDisable();
        } catch (Throwable error) {
            LOGGER.error("Iris service onDisable failed for {}", service.getClass().getName(), error);
        }
    }

    private void tickService(ModdedTickableService service, MinecraftServer server) {
        try {
            service.onServerTick(server);
        } catch (Throwable error) {
            LOGGER.error("Iris service tick failed for {}", service.getClass().getName(), error);
        }
    }

    private void forEachReversed(Consumer<ModdedService> action) {
        ModdedService[] ordered = services.values().toArray(new ModdedService[0]);
        for (int i = ordered.length - 1; i >= 0; i--) {
            action.accept(ordered[i]);
        }
    }
}
