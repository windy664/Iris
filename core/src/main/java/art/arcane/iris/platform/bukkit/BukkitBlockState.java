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
    private volatile Boolean fluid;
    private volatile Boolean water;
    private volatile Boolean foliage;
    private volatile Boolean decorant;

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
            cached = BukkitBlockResolution.isAir(data);
            air = cached;
        }
        return cached;
    }

    @Override
    public boolean isSolid() {
        Boolean cached = solid;
        if (cached == null) {
            cached = BukkitBlockResolution.isSolid(data);
            solid = cached;
        }
        return cached;
    }

    @Override
    public boolean isFluid() {
        Boolean cached = fluid;
        if (cached == null) {
            cached = BukkitBlockResolution.isFluid(data);
            fluid = cached;
        }
        return cached;
    }

    @Override
    public boolean isWater() {
        Boolean cached = water;
        if (cached == null) {
            cached = BukkitBlockResolution.isWater(data);
            water = cached;
        }
        return cached;
    }

    @Override
    public boolean isWaterLogged() {
        return BukkitBlockResolution.isWaterLogged(data);
    }

    @Override
    public boolean isLit() {
        return BukkitBlockResolution.isLit(data);
    }

    @Override
    public boolean isUpdatable() {
        return BukkitBlockResolution.isUpdatable(data);
    }

    @Override
    public boolean isFoliage() {
        Boolean cached = foliage;
        if (cached == null) {
            cached = BukkitBlockResolution.isFoliage(data);
            foliage = cached;
        }
        return cached;
    }

    @Override
    public boolean isFoliagePlantable() {
        return BukkitBlockResolution.isFoliagePlantable(data);
    }

    @Override
    public boolean isDecorant() {
        Boolean cached = decorant;
        if (cached == null) {
            cached = BukkitBlockResolution.isDecorant(data);
            decorant = cached;
        }
        return cached;
    }

    @Override
    public boolean isStorage() {
        return BukkitBlockResolution.isStorage(data);
    }

    @Override
    public boolean isStorageChest() {
        return BukkitBlockResolution.isStorageChest(data);
    }

    @Override
    public boolean isOre() {
        return BukkitBlockResolution.isOre(data);
    }

    @Override
    public boolean isDeepSlate() {
        return BukkitBlockResolution.isDeepSlate(data);
    }

    @Override
    public boolean isVineBlock() {
        return BukkitBlockResolution.isVineBlock(data);
    }

    @Override
    public boolean canPlaceOnto(PlatformBlockState onto) {
        return BukkitBlockResolution.canPlaceOnto(data.getMaterial(), ((BlockData) onto.nativeHandle()).getMaterial());
    }

    @Override
    public boolean matches(PlatformBlockState state) {
        return data.matches((BlockData) state.nativeHandle());
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
