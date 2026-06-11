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
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformEntityType;
import art.arcane.iris.spi.PlatformItem;
import art.arcane.iris.spi.PlatformRegistries;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit adapter resolving namespaced keys against the live Bukkit registries.
 */
public final class BukkitRegistries implements PlatformRegistries {
    @Override
    public PlatformBlockState block(String key) {
        BlockData data = BukkitBlockResolution.get(key);
        return data == null ? null : BukkitBlockState.of(data);
    }

    @Override
    public PlatformBlockState blockOrNull(String key) {
        BlockData data = BukkitBlockResolution.getOrNull(key);
        return data == null ? null : BukkitBlockState.of(data);
    }

    @Override
    public PlatformBlockState blockOrNull(String key, boolean warn) {
        BlockData data = BukkitBlockResolution.getOrNull(key, warn);
        return data == null ? null : BukkitBlockState.of(data);
    }

    @Override
    public PlatformBlockState air() {
        return BukkitBlockState.of(BukkitBlockResolution.getAir());
    }

    @Override
    public PlatformBlockState deepSlateOre(PlatformBlockState block, PlatformBlockState ore) {
        return BukkitBlockState.of(BukkitBlockResolution.toDeepSlateOre((BlockData) block.nativeHandle(), (BlockData) ore.nativeHandle()));
    }

    @Override
    public PlatformBiome biome(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) {
            return null;
        }
        Biome biome = Registry.BIOME.get(namespacedKey);
        return biome == null ? null : BukkitBiome.of(biome);
    }

    @Override
    public PlatformItem item(String key) {
        Material material = Material.matchMaterial(key);
        return material == null ? null : BukkitItem.of(material);
    }

    @Override
    public PlatformEntityType entity(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) {
            return null;
        }
        EntityType type = Registry.ENTITY_TYPE.get(namespacedKey);
        return type == null ? null : BukkitEntityType.of(type);
    }

    @Override
    public List<String> blockKeys() {
        List<String> keys = new ArrayList<>();
        for (Material material : Registry.MATERIAL) {
            if (material.isBlock()) {
                keys.add(material.getKey().toString());
            }
        }
        return keys;
    }

    @Override
    public List<String> biomeKeys() {
        List<String> keys = new ArrayList<>();
        for (Biome biome : Registry.BIOME) {
            keys.add(BukkitBiome.of(biome).key());
        }
        return keys;
    }

    @Override
    public List<String> structureKeys() {
        return new ArrayList<>(INMS.get().getStructureKeys());
    }
}
