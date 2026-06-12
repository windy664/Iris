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

import art.arcane.iris.engine.object.BlockDataMergeSupport;
import art.arcane.iris.spi.PlatformBlockState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;

public final class FabricStateMerger implements BlockDataMergeSupport.StateMerger {
    @Override
    public PlatformBlockState merge(PlatformBlockState base, PlatformBlockState update) {
        FabricBlockState fabricBase = (FabricBlockState) base;
        FabricBlockState fabricUpdate = (FabricBlockState) update;

        try {
            return FabricBlockState.of(mergeStates(fabricBase.handle(), fabricUpdate.handle(), fabricUpdate.parsedProperties()), null);
        } catch (IllegalArgumentException e) {
            FabricBlockResolution.Parsed normalizedBase = FabricBlockResolution.resolveGet(FabricBlockState.serialize(fabricBase.handle()));
            FabricBlockResolution.Parsed normalizedUpdate = FabricBlockResolution.resolveGet(FabricBlockState.serialize(fabricUpdate.handle()));

            if (normalizedBase != null && normalizedUpdate != null) {
                try {
                    return FabricBlockState.of(mergeStates(normalizedBase.state(), normalizedUpdate.state(), normalizedUpdate.properties()), null);
                } catch (IllegalArgumentException ignored) {
                    return FabricBlockState.of(normalizedUpdate.state(), normalizedUpdate.properties());
                }
            }

            if (normalizedUpdate != null) {
                return FabricBlockState.of(normalizedUpdate.state(), normalizedUpdate.properties());
            }

            return update;
        }
    }

    private static BlockState mergeStates(BlockState base, BlockState update, Map<Property<?>, Comparable<?>> parsedProperties) {
        if (parsedProperties == null) {
            throw new IllegalArgumentException("Block data not created via string parsing");
        }
        if (base.getBlock() != update.getBlock()) {
            throw new IllegalArgumentException("States have different types (got " + update.getBlock() + ", expected " + base.getBlock() + ")");
        }
        BlockState merged = base;
        for (Map.Entry<Property<?>, Comparable<?>> entry : parsedProperties.entrySet()) {
            merged = apply(merged, entry.getKey(), entry.getValue());
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<?> property, Comparable<?> value) {
        return state.setValue((Property<T>) property, (T) value);
    }
}
