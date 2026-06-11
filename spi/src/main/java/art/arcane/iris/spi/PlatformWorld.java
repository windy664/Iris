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

package art.arcane.iris.spi;

/**
 * Neutral view of a loaded world for edit and lifecycle paths; never used on the generation hot path.
 */
public interface PlatformWorld {
    String name();

    long seed();

    int minHeight();

    int maxHeight();

    PlatformBlockState getBlock(int x, int y, int z);

    void setBlock(int x, int y, int z, PlatformBlockState block, int flags);

    PlatformBiome getBiome(int x, int y, int z);

    boolean isChunkLoaded(int chunkX, int chunkZ);

    long getTime();

    boolean isStorming();

    boolean isThundering();

    Object nativeHandle();
}
