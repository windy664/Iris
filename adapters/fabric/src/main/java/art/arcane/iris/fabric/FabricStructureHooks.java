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

import art.arcane.iris.spi.PlatformStructureHooks;
import art.arcane.iris.spi.PlatformWorld;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class FabricStructureHooks implements PlatformStructureHooks {
    private final Supplier<MinecraftServer> server;

    public FabricStructureHooks(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public List<String> structureKeys() {
        return registryKeys(Registries.STRUCTURE);
    }

    @Override
    public List<String> structureSetKeys() {
        return registryKeys(Registries.STRUCTURE_SET);
    }

    @Override
    public List<String> structureBiomeKeys(String structureKey) {
        List<String> keys = new ArrayList<>();
        MinecraftServer instance = server.get();
        if (instance == null) {
            return keys;
        }
        Identifier identifier = Identifier.tryParse(structureKey);
        if (identifier == null) {
            return keys;
        }
        Registry<Structure> registry = instance.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Structure structure = registry.getValue(identifier);
        if (structure == null) {
            return keys;
        }
        for (Holder<Biome> holder : structure.biomes()) {
            holder.unwrapKey().ifPresent((net.minecraft.resources.ResourceKey<Biome> key) -> keys.add(key.identifier().toString()));
        }
        return keys;
    }

    @Override
    public List<String> objectFeatureKeys() {
        return registryKeys(Registries.CONFIGURED_FEATURE);
    }

    @Override
    public List<String> reachableStructureKeys(PlatformWorld world) {
        return List.of();
    }

    @Override
    public List<String> possibleBiomeKeys(PlatformWorld world) {
        return List.of();
    }

    @Override
    public boolean placeFeature(PlatformWorld world, int x, int y, int z, String featureKey, long seed) {
        return false;
    }

    @Override
    public int[] placeStructure(PlatformWorld world, int chunkX, int chunkZ, String structureKey, long seed, int maxSpan) {
        return null;
    }

    @Override
    public boolean supportsStructurePlacement() {
        return false;
    }

    private <T> List<String> registryKeys(net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey) {
        List<String> keys = new ArrayList<>();
        MinecraftServer instance = server.get();
        if (instance == null) {
            return keys;
        }
        Registry<T> registry = instance.registryAccess().lookupOrThrow(registryKey);
        for (Identifier identifier : registry.keySet()) {
            keys.add(identifier.toString());
        }
        return keys;
    }
}
