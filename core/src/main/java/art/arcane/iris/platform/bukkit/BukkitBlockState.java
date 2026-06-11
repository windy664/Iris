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

package art.arcane.iris.platform.bukkit;

import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.common.data.IrisCustomData;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interned Bukkit adapter for a neutral block state backed by BlockData.
 */
public final class BukkitBlockState implements PlatformBlockState {
    private static final ConcurrentHashMap<String, BukkitBlockState> CACHE = new ConcurrentHashMap<>();

    private final BlockData data;
    private final String key;
    private final String namespace;
    private volatile Boolean air;
    private volatile Boolean solid;

    private BukkitBlockState(BlockData data, String key) {
        this.data = data;
        this.key = key;
        this.namespace = parseNamespace(key);
    }

    public static BukkitBlockState of(BlockData data) {
        if (data instanceof IrisCustomData custom) {
            return new BukkitBlockState(data, custom.getAsString());
        }
        String key = data.getAsString();
        return CACHE.computeIfAbsent(key, (String k) -> new BukkitBlockState(data, k));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BukkitBlockState state)) {
            return false;
        }
        return data.equals(state.data);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    private static String parseNamespace(String key) {
        String base = key;
        int bracket = base.indexOf('[');
        if (bracket >= 0) {
            base = base.substring(0, bracket);
        }
        int colon = base.indexOf(':');
        return colon >= 0 ? base.substring(0, colon) : "minecraft";
    }

    private static String mergeProperty(String key, String name, String value) {
        int bracket = key.indexOf('[');
        if (bracket < 0) {
            return key + "[" + name + "=" + value + "]";
        }
        String base = key.substring(0, bracket);
        String body = key.substring(bracket + 1, key.lastIndexOf(']'));
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        for (String entry : body.split(",")) {
            int equals = entry.indexOf('=');
            if (equals < 0) {
                continue;
            }
            properties.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
        }
        properties.put(name, value);
        StringBuilder merged = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (!first) {
                merged.append(',');
            }
            merged.append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        return merged.append(']').toString();
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public boolean isAir() {
        Boolean cached = air;
        if (cached == null) {
            cached = B.isAir(data);
            air = cached;
        }
        return cached;
    }

    @Override
    public boolean isSolid() {
        Boolean cached = solid;
        if (cached == null) {
            cached = B.isSolid(data);
            solid = cached;
        }
        return cached;
    }

    @Override
    public boolean hasTileEntity() {
        return INMS.get().hasTile(data.getMaterial());
    }

    @Override
    public PlatformBlockState withProperty(String name, String value) {
        String merged = mergeProperty(key, name, value);
        BlockData resolved = Bukkit.createBlockData(merged);
        return of(resolved);
    }

    @Override
    public Object nativeHandle() {
        return data;
    }
}
