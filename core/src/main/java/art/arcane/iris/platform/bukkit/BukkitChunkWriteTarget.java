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

import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.spi.ChunkWriteTarget;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

/**
 * Bukkit adapter for the hot-path chunk write surface backed by TerrainChunk.
 */
public final class BukkitChunkWriteTarget implements ChunkWriteTarget {
    private final TerrainChunk terrain;

    public BukkitChunkWriteTarget(TerrainChunk terrain) {
        this.terrain = terrain;
    }

    @Override
    public void setBlock(int x, int y, int z, PlatformBlockState state) {
        terrain.setBlock(x, y, z, (BlockData) state.nativeHandle());
    }

    @Override
    public void setBiome(int x, int y, int z, PlatformBiome biome) {
        terrain.setBiome(x, y, z, (Biome) biome.nativeHandle());
    }

    @Override
    public int minHeight() {
        return terrain.getMinHeight();
    }

    @Override
    public int maxHeight() {
        return terrain.getMaxHeight();
    }
}
