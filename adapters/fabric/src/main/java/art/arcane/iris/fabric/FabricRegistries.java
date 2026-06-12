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

import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformEntityType;
import art.arcane.iris.spi.PlatformItem;
import art.arcane.iris.spi.PlatformRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class FabricRegistries implements PlatformRegistries {
    private final Supplier<MinecraftServer> server;

    public FabricRegistries(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public PlatformBlockState block(String key) {
        return FabricBlockResolution.get(key);
    }

    @Override
    public PlatformBlockState blockOrNull(String key) {
        return FabricBlockResolution.getOrNull(key);
    }

    @Override
    public PlatformBlockState blockOrNull(String key, boolean warn) {
        return FabricBlockResolution.getOrNull(key, warn);
    }

    @Override
    public PlatformBlockState air() {
        return FabricBlockResolution.getAir();
    }

    @Override
    public PlatformBlockState deepSlateOre(PlatformBlockState block, PlatformBlockState ore) {
        BlockState result = FabricBlockResolution.toDeepSlateOre((BlockState) block.nativeHandle(), (BlockState) ore.nativeHandle());
        return FabricBlockState.of(result, null);
    }

    @Override
    public PlatformBiome biome(String key) {
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null) {
            return null;
        }
        Registry<Biome> registry = biomeRegistry();
        if (registry == null) {
            return null;
        }
        Biome biome = registry.getValue(identifier);
        return biome == null ? null : FabricBiome.of(biome, identifier.toString());
    }

    @Override
    public PlatformItem item(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        Identifier identifier = Identifier.tryParse("minecraft:" + normalized);
        if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(identifier);
        return FabricItem.of(item, identifier.toString());
    }

    @Override
    public PlatformEntityType entity(String key) {
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(identifier);
        return FabricEntityType.of(type, identifier.toString());
    }

    @Override
    public List<String> blockKeys() {
        List<String> keys = new ArrayList<>();
        for (Identifier identifier : BuiltInRegistries.BLOCK.keySet()) {
            keys.add(identifier.toString());
        }
        return keys;
    }

    @Override
    public List<String> biomeKeys() {
        List<String> keys = new ArrayList<>();
        Registry<Biome> registry = biomeRegistry();
        if (registry == null) {
            return keys;
        }
        for (Identifier identifier : registry.keySet()) {
            keys.add(identifier.toString());
        }
        return keys;
    }

    @Override
    public List<String> structureKeys() {
        List<String> keys = new ArrayList<>();
        MinecraftServer instance = server.get();
        if (instance == null) {
            return keys;
        }
        for (Identifier identifier : instance.registryAccess().lookupOrThrow(Registries.STRUCTURE).keySet()) {
            keys.add(identifier.toString());
        }
        return keys;
    }

    private Registry<Biome> biomeRegistry() {
        MinecraftServer instance = server.get();
        if (instance == null) {
            return null;
        }
        return instance.registryAccess().lookupOrThrow(Registries.BIOME);
    }
}
