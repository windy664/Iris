/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.engine.object;

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.math.Vector3i;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.block.data.BlockData;

import java.util.Map;

/**
 * Shared low-level helpers for building procedural objects in memory: resolving a
 * single block id or a noise palette into BlockData, and assembling a raw block
 * map into a centered IrisObject anchored so its y=0 layer sits one block above
 * the terrain surface (same convention the tree generator uses).
 */
public final class IrisProceduralBlocks {
    private IrisProceduralBlocks() {
    }

    public static boolean paletteSet(IrisMaterialPalette palette) {
        return palette != null && palette.getPalette() != null && !palette.getPalette().isEmpty();
    }

    public static BlockData resolve(String block, IrisMaterialPalette palette, IrisData data, int x, int y, int z, RNG paletteRng) {
        if (paletteSet(palette)) {
            PlatformBlockState state = palette.get(paletteRng, x, y, z, data);
            return state == null ? null : ((BlockData) state.nativeHandle()).clone();
        }
        if (block != null && !block.isEmpty()) {
            BlockData bd = BukkitBlockResolution.getOrNull(block, false);
            return bd == null ? null : bd.clone();
        }
        return null;
    }

    public static IrisObject assemble(Map<Vector3i, BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Vector3i v : blocks.keySet()) {
            minX = Math.min(minX, v.getBlockX());
            minY = Math.min(minY, v.getBlockY());
            minZ = Math.min(minZ, v.getBlockZ());
            maxX = Math.max(maxX, v.getBlockX());
            maxY = Math.max(maxY, v.getBlockY());
            maxZ = Math.max(maxZ, v.getBlockZ());
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int d = maxZ - minZ + 1;
        int cx = w / 2;
        int cy = h / 2;
        int cz = d / 2;

        IrisObject object = new IrisObject(w, h, d);
        for (Map.Entry<Vector3i, BlockData> entry : blocks.entrySet()) {
            Vector3i v = entry.getKey();
            int nx = v.getBlockX() - minX - cx;
            int ny = v.getBlockY() - cy + 1;
            int nz = v.getBlockZ() - minZ - cz;
            object.getBlocks().put(new Vector3i(nx, ny, nz), BukkitBlockState.of(entry.getValue()));
        }

        return object;
    }
}
