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

package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.FloatingIslandSample;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.util.common.data.B;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

public class IslandObjectPlacer implements IObjectPlacer {
    private static final int OVERHANG_RADIUS = 2;

    public enum AnchorFace { TOP, BOTTOM }

    private final MantleWriter wrapped;
    private final FloatingIslandSample[] samples;
    private final boolean[] overhangAllowed;
    private final int minX;
    private final int minZ;
    private final int chunkMaxIslandTopY;
    private final int chunkMinIslandBottomY;
    private final int anchorY;
    private final AnchorFace face;
    private int writesAttempted;
    private int writesDroppedBelow;
    private int writesDroppedOverhang;
    private int writesDroppedAboveBottom;
    private int writesDroppedBottomOverhang;

    public IslandObjectPlacer(MantleWriter wrapped, FloatingIslandSample[] samples, int minX, int minZ, int anchorTopY) {
        this(wrapped, samples, minX, minZ, anchorTopY, AnchorFace.TOP);
    }

    public IslandObjectPlacer(MantleWriter wrapped, FloatingIslandSample[] samples, int minX, int minZ, int anchorY, AnchorFace face) {
        this.wrapped = wrapped;
        this.samples = samples;
        this.minX = minX;
        this.minZ = minZ;
        this.anchorY = anchorY;
        this.face = face;
        int maxTopY = -1;
        int minBottomY = Integer.MAX_VALUE;
        for (FloatingIslandSample s : samples) {
            if (s != null) {
                int ty = s.topY();
                if (ty > maxTopY) maxTopY = ty;
                int by = s.bottomY();
                if (by >= 0 && by < minBottomY) minBottomY = by;
            }
        }
        this.chunkMaxIslandTopY = maxTopY;
        this.chunkMinIslandBottomY = (minBottomY == Integer.MAX_VALUE) ? -1 : minBottomY;
        this.overhangAllowed = buildOverhangMask(samples);
    }

    private static boolean[] buildOverhangMask(FloatingIslandSample[] samples) {
        boolean[] mask = new boolean[256];
        for (int zf = 0; zf < 16; zf++) {
            for (int xf = 0; xf < 16; xf++) {
                int idx = (zf << 4) | xf;
                if (samples[idx] != null) {
                    mask[idx] = true;
                    continue;
                }
                boolean touchedEdge = false;
                boolean found = false;
                for (int dz = -OVERHANG_RADIUS; dz <= OVERHANG_RADIUS && !found; dz++) {
                    int nzf = zf + dz;
                    for (int dx = -OVERHANG_RADIUS; dx <= OVERHANG_RADIUS; dx++) {
                        int nxf = xf + dx;
                        if (nxf < 0 || nxf >= 16 || nzf < 0 || nzf >= 16) {
                            touchedEdge = true;
                            continue;
                        }
                        if (samples[(nzf << 4) | nxf] != null) {
                            found = true;
                            break;
                        }
                    }
                }
                mask[idx] = found || touchedEdge;
            }
        }
        return mask;
    }

    public int getWritesAttempted() {
        return writesAttempted;
    }

    public int getWritesDroppedBelow() {
        return writesDroppedBelow;
    }

    public int getWritesDroppedOverhang() {
        return writesDroppedOverhang;
    }

    public int getWritesDroppedAboveBottom() {
        return writesDroppedAboveBottom;
    }

    public int getWritesDroppedBottomOverhang() {
        return writesDroppedBottomOverhang;
    }

    private boolean shouldSkipAirColumn(int x, int y, int z) {
        return shouldSkipAirColumn(x, y, z, true);
    }

    private boolean shouldSkipAirColumn(int x, int y, int z, boolean countWrite) {
        if (countWrite) {
            writesAttempted++;
        }
        int xf = x - minX;
        int zf = z - minZ;
        if (xf >= 0 && xf < 16 && zf >= 0 && zf < 16) {
            int idx = (zf << 4) | xf;
            if (samples[idx] != null) {
                if (face == AnchorFace.TOP) {
                    return false;
                }
                if (y >= anchorY) {
                    if (countWrite) {
                        writesDroppedAboveBottom++;
                    }
                    return true;
                }
                return false;
            }
            if (face == AnchorFace.TOP) {
                if (y <= anchorY) {
                    if (countWrite) {
                        writesDroppedBelow++;
                    }
                    return true;
                }
                if (!overhangAllowed[idx]) {
                    if (countWrite) {
                        writesDroppedOverhang++;
                    }
                    return true;
                }
            } else {
                if (y >= anchorY) {
                    if (countWrite) {
                        writesDroppedBottomOverhang++;
                    }
                    return true;
                }
                if (!overhangAllowed[idx]) {
                    if (countWrite) {
                        writesDroppedBottomOverhang++;
                    }
                    return true;
                }
            }
            return false;
        }
        if (face == AnchorFace.TOP) {
            if (y <= anchorY) {
                if (countWrite) {
                    writesDroppedBelow++;
                }
                return true;
            }
        } else {
            if (y >= anchorY) {
                if (countWrite) {
                    writesDroppedBottomOverhang++;
                }
                return true;
            }
        }
        if (countWrite) {
            writesDroppedOverhang++;
        }
        return true;
    }

    public boolean canWriteObjectBlock(int x, int y, int z) {
        return !shouldSkipAirColumn(x, y, z, false);
    }

    private @Nullable FloatingIslandSample sampleAt(int x, int z) {
        int xf = x - minX;
        int zf = z - minZ;
        if (xf < 0 || xf >= 16 || zf < 0 || zf >= 16) {
            return null;
        }
        return samples[(zf << 4) | xf];
    }

    @Override
    public int getHighest(int x, int z, IrisData data) {
        FloatingIslandSample s = sampleAt(x, z);
        if (face == AnchorFace.TOP) {
            if (s != null) return s.topY();
            return chunkMaxIslandTopY;
        }
        if (s != null) {
            int by = s.bottomY();
            return (by >= 0) ? by : chunkMinIslandBottomY;
        }
        return chunkMinIslandBottomY;
    }

    @Override
    public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
        return getHighest(x, z, data);
    }

    @Override
    public boolean isUnderwater(int x, int z) {
        return false;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        FloatingIslandSample s = sampleAt(x, z);
        if (s != null) {
            int idx = y - s.islandBaseY;
            if (idx >= 0 && idx < s.solidMask.length) {
                return s.solidMask[idx];
            }
            return false;
        }
        return wrapped.isSolid(x, y, z);
    }

    @Override
    public boolean isCarved(int x, int y, int z) {
        return wrapped.isCarved(x, y, z);
    }

    @Override
    public void set(int x, int y, int z, BlockData d) {
        if (shouldSkipAirColumn(x, y, z)) {
            return;
        }
        wrapped.set(x, y, z, d);
    }

    @Override
    public BlockData get(int x, int y, int z) {
        return wrapped.get(x, y, z);
    }

    @Override
    public boolean isPreventingDecay() {
        return wrapped.isPreventingDecay();
    }

    @Override
    public int getFluidHeight() {
        return wrapped.getFluidHeight();
    }

    @Override
    public boolean isDebugSmartBore() {
        return wrapped.isDebugSmartBore();
    }

    @Override
    public void setTile(int xx, int yy, int zz, TileData tile) {
        if (shouldSkipAirColumn(xx, yy, zz)) {
            return;
        }
        wrapped.setTile(xx, yy, zz, tile);
    }

    @Override
    public <T> void setData(int xx, int yy, int zz, T data) {
        if (shouldSkipAirColumn(xx, yy, zz)) {
            return;
        }
        wrapped.setData(xx, yy, zz, data);
    }

    @Override
    public <T> @Nullable T getData(int xx, int yy, int zz, Class<T> t) {
        return wrapped.getData(xx, yy, zz, t);
    }

    @Override
    public Engine getEngine() {
        return wrapped.getEngine();
    }
}
