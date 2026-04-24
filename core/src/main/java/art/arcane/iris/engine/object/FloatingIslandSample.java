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

import art.arcane.iris.Iris;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.volmlib.util.collection.KList;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FloatingIslandSample {
    public static final int REJECT_NONE = 0;
    public static final int REJECT_NO_ENTRIES = 1;
    public static final int REJECT_NO_SEED = 2;
    public static final int REJECT_NO_PICK = 3;
    public static final int REJECT_ABOVE_HEIGHT = 4;
    public static final int REJECT_NO_THICKNESS = 5;
    public static final int REJECT_NO_SOLID = 6;
    public static final int REJECT_COUNT = 7;
    public static final int REJECT_CLUSTER = REJECT_NO_SEED;

    private static final double EDGE_ROUNDING_BAND = 0.28;
    private static final ThreadLocal<int[]> LAST_REJECT = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<double[]> LAST_DENSITY = ThreadLocal.withInitial(() -> new double[2]);
    private static final ThreadLocal<HashMap<Long, FloatingIslandSample>> CHUNK_MEMO = ThreadLocal.withInitial(HashMap::new);
    private static final AtomicBoolean NULL_CNG_WARNED = new AtomicBoolean(false);

    public static int getLastReject() {
        return LAST_REJECT.get()[0];
    }

    public static double getLastClusterValue() {
        return LAST_DENSITY.get()[0];
    }

    public static double getLastClusterThreshold() {
        return LAST_DENSITY.get()[1];
    }

    public static void clearThreadCaches() {
    }

    public static void clearChunkMemo() {
        CHUNK_MEMO.get().clear();
    }

    public static FloatingIslandSample sampleMemoized(IrisBiome parent, int wx, int wz, int chunkHeight, long baseSeed, IrisData data, Engine engine) {
        long key = (((long) wx) << 32) ^ (wz & 0xFFFFFFFFL);
        HashMap<Long, FloatingIslandSample> memo = CHUNK_MEMO.get();
        if (memo.containsKey(key)) {
            return memo.get(key);
        }
        FloatingIslandSample result = sample(parent, wx, wz, chunkHeight, baseSeed, data, engine);
        memo.put(key, result);
        return result;
    }

    private static FloatingIslandSample reject(int code) {
        LAST_REJECT.get()[0] = code;
        return null;
    }

    private static void warnNullCng(String styleField, IrisBiome parent) {
        if (NULL_CNG_WARNED.compareAndSet(false, true)) {
            String biomeKey = parent == null ? "<unknown>" : parent.getLoadKey();
            Iris.warn("Floating child biome on " + biomeKey + " has a null CNG for " + styleField
                    + " (style factory returned null or AtomicCache swallowed an exception); skipping floating sampling until pack is fixed");
        }
    }

    public final IrisFloatingChildBiomes entry;
    public final int islandBaseY;
    public final int thickness;
    public final int topIdx;
    public final int solidCount;
    public final boolean[] solidMask;
    private transient int cachedBottomIdx = -2;

    private FloatingIslandSample(IrisFloatingChildBiomes entry, int islandBaseY, int thickness, int topIdx, int solidCount, boolean[] solidMask) {
        this.entry = entry;
        this.islandBaseY = islandBaseY;
        this.thickness = thickness;
        this.topIdx = topIdx;
        this.solidCount = solidCount;
        this.solidMask = solidMask;
    }

    static FloatingIslandSample constructForTest(int islandBaseY, int thickness, int topIdx, int solidCount, boolean[] solidMask) {
        return new FloatingIslandSample(null, islandBaseY, thickness, topIdx, solidCount, solidMask);
    }

    public int topY() {
        return islandBaseY + topIdx;
    }

    public int bottomY() {
        if (cachedBottomIdx == -2) {
            cachedBottomIdx = -1;
            for (int i = 0; i < solidMask.length; i++) {
                if (solidMask[i]) {
                    cachedBottomIdx = i;
                    break;
                }
            }
        }
        return cachedBottomIdx == -1 ? -1 : islandBaseY + cachedBottomIdx;
    }

    public static long columnSeed(long baseSeed, int wx, int wz) {
        return baseSeed ^ ((long) wx * 341873128712L) ^ ((long) wz * 132897987541L);
    }

    public static FloatingIslandSample sample(IrisBiome parent, int wx, int wz, int chunkHeight, long baseSeed, IrisData data, Engine engine) {
        KList<IrisFloatingChildBiomes> entries = parent.getFloatingChildBiomes();
        if (entries == null || entries.isEmpty()) {
            return reject(REJECT_NO_ENTRIES);
        }

        IrisFloatingChildBiomes entry;
        if (entries.size() == 1) {
            entry = entries.getFirst();
        } else {
            IrisFloatingChildBiomes reference = entries.getFirst();
            CNG picker = reference.getPickerCng(baseSeed, data);
            if (picker == null) {
                warnNullCng("pickerStyle", parent);
                return reject(REJECT_NO_PICK);
            }
            double pickerValue = picker.noise(wx, wz);
            double clamped = Math.max(0, Math.min(1, pickerValue));
            entry = IRare.pick(entries, clamped);
            if (entry == null) {
                return reject(REJECT_NO_PICK);
            }
        }

        CNG footprintCng = entry.getFootprintCng(baseSeed, data);
        if (footprintCng == null) {
            warnNullCng("footprintStyle", parent);
            return reject(REJECT_NO_SEED);
        }
        double footprintValue = footprintCng.noise(wx, wz);
        double signed = (Math.max(0, Math.min(1, footprintValue)) * 2.0) - 1.0;
        double threshold = Math.max(0, Math.min(1, entry.getFootprintThreshold()));
        double signedCut = (threshold * 2.0) - 1.0;

        double[] diag = LAST_DENSITY.get();
        diag[0] = signed;
        diag[1] = signedCut;

        if (signed <= signedCut) {
            return reject(REJECT_NO_SEED);
        }
        if (!hasFootprintNeighborSupport(footprintCng, wx, wz, signedCut)) {
            return reject(REJECT_NO_SEED);
        }

        CNG altitudeCng = entry.getAltitudeCng(baseSeed, data);
        if (altitudeCng == null) {
            warnNullCng("altitudeStyle", parent);
            return reject(REJECT_NO_SEED);
        }
        double altNoise = altitudeCng.noise(wx, wz);
        double altClamped = Math.max(0, Math.min(1, altNoise));
        int worldMin = engine.getWorld().minHeight();
        int minAlt = Math.max(0, entry.getMinHeightAboveSurface() - worldMin);
        int maxAlt = Math.max(minAlt, entry.getMaxHeightAboveSurface() - worldMin);
        int baseY = minAlt + (int) Math.round(altClamped * (maxAlt - minAlt));

        double edgeFade = edgeFade(signed, signedCut);
        IrisBiome target = entry.getRealBiome(parent, data);
        int topH = roundedEdgeHeight(computeTopHeight(entry, target, engine, baseSeed, wx, wz, data), edgeFade);
        int topY = baseY + topH;

        CNG bottomCng = entry.getBottomCng(baseSeed, data);
        if (bottomCng == null) {
            warnNullCng("bottomStyle", parent);
            return reject(REJECT_NO_SEED);
        }
        double bottomNoise = bottomCng.noise(wx, wz);
        double bottomClamped = Math.max(0, Math.min(1, bottomNoise));
        double bottomShaped = Math.pow(bottomClamped, Math.max(0.1, entry.getBottomExponent()));
        int minDepth = Math.max(0, entry.getBottomDepthMin());
        int maxDepth = Math.max(minDepth, entry.getBottomDepthMax());
        int depth = roundedEdgeDepth(minDepth, maxDepth, bottomShaped, edgeFade);
        int botY = baseY - depth;

        Integer minAbsoluteY = entry.getMinAbsoluteY();
        if (minAbsoluteY != null && botY < minAbsoluteY - worldMin) {
            botY = minAbsoluteY - worldMin;
        }
        Integer maxAbsoluteY = entry.getMaxAbsoluteY();
        if (maxAbsoluteY != null && topY > maxAbsoluteY - worldMin) {
            topY = maxAbsoluteY - worldMin;
        }

        if (botY < 0) {
            botY = 0;
        }
        if (topY >= chunkHeight) {
            topY = chunkHeight - 1;
        }
        if (topY < botY) {
            return reject(REJECT_ABOVE_HEIGHT);
        }

        int thickness = topY - botY + 1;
        int maxThickness = Math.max(1, entry.getMaxThickness());
        if (thickness > maxThickness) {
            botY = topY - maxThickness + 1;
            if (botY < 0) {
                botY = 0;
            }
            thickness = topY - botY + 1;
        }
        if (thickness <= 0) {
            return reject(REJECT_NO_THICKNESS);
        }

        boolean[] solidMask = new boolean[thickness];
        CNG wallWarp = entry.getWallWarpCng(baseSeed, data);
        double warpAmp = Math.max(0, entry.getWallWarpAmplitude());
        CNG carve = entry.getCarveCng(baseSeed, data);
        double carveThreshold = entry.getCarveThreshold();
        boolean useWarp = wallWarp != null && warpAmp > 0;
        boolean useCarve = carve != null && carveThreshold < 1.0;

        for (int k = 0; k < thickness; k++) {
            int wy = botY + k;
            double sx = wx;
            double sz = wz;
            if (useWarp) {
                double wnX = wallWarp.noise(wx, wy, wz);
                double signedWarpX = (Math.max(0, Math.min(1, wnX)) * 2.0) - 1.0;
                sx = wx + signedWarpX * warpAmp;
                double wnZ = wallWarp.noise(wx + 1987.3, wy, wz + 2341.1);
                double signedWarpZ = (Math.max(0, Math.min(1, wnZ)) * 2.0) - 1.0;
                sz = wz + signedWarpZ * warpAmp;
            }
            double layerFoot = footprintCng.noise(sx, sz);
            double layerSigned = (Math.max(0, Math.min(1, layerFoot)) * 2.0) - 1.0;
            if (layerSigned <= signedCut) {
                continue;
            }
            solidMask[k] = true;
        }

        int solidCount = solidifyUncarvedInterior(solidMask);
        if (useCarve) {
            solidCount = carveSolidInterior(solidMask, botY, wx, wz, carve, carveThreshold);
        }
        int highestSolidIdx = highestSolidIndex(solidMask);

        if (solidCount == 0 || highestSolidIdx < 0) {
            return reject(REJECT_NO_SOLID);
        }

        int topIdx = highestSolidIdx;

        LAST_REJECT.get()[0] = REJECT_NONE;
        return new FloatingIslandSample(entry, botY, thickness, topIdx, solidCount, solidMask);
    }

    static double edgeFade(double signed, double signedCut) {
        double edge = (signed - signedCut) / EDGE_ROUNDING_BAND;
        double edgeClamped = Math.max(0, Math.min(1, edge));
        return edgeClamped * edgeClamped * (3.0 - 2.0 * edgeClamped);
    }

    static int roundedEdgeHeight(int topHeight, double edgeFade) {
        return Math.max(0, (int) Math.round(Math.max(0, topHeight) * Math.max(0, Math.min(1, edgeFade))));
    }

    static int roundedEdgeDepth(int minDepth, int maxDepth, double bottomShaped, double edgeFade) {
        int min = Math.max(0, minDepth);
        int max = Math.max(min, maxDepth);
        double shaped = Math.max(0, Math.min(1, bottomShaped));
        double fade = Math.max(0, Math.min(1, edgeFade));
        double fullDepth = min + shaped * (max - min);
        return (int) Math.round(fullDepth * fade);
    }

    static int carveSolidInterior(boolean[] solidMask, int botY, int wx, int wz, CNG carve, double carveThreshold) {
        int firstSolid = -1;
        int lastSolid = -1;
        for (int i = 0; i < solidMask.length; i++) {
            if (!solidMask[i]) {
                continue;
            }
            if (firstSolid < 0) {
                firstSolid = i;
            }
            lastSolid = i;
        }
        if (firstSolid < 0) {
            return 0;
        }
        int count = 0;
        for (int i = firstSolid; i <= lastSolid; i++) {
            if (i != firstSolid && i != lastSolid) {
                double carveNoise = carve.noise(wx, botY + i, wz);
                double carveClamped = Math.max(0, Math.min(1, carveNoise));
                if (carveClamped > carveThreshold) {
                    solidMask[i] = false;
                    continue;
                }
            }
            if (solidMask[i]) {
                count++;
            }
        }
        return count;
    }

    static boolean hasFootprintNeighborSupport(CNG footprintCng, int wx, int wz, double signedCut) {
        int cardinal = 0;
        int diagonal = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                double footprintValue = footprintCng.noise(wx + dx, wz + dz);
                double signed = (Math.max(0, Math.min(1, footprintValue)) * 2.0) - 1.0;
                if (signed <= signedCut) {
                    continue;
                }
                if (Math.abs(dx) + Math.abs(dz) == 1) {
                    cardinal++;
                } else {
                    diagonal++;
                }
            }
        }
        return cardinal > 0 || diagonal >= 2;
    }

    static int solidifyUncarvedInterior(boolean[] solidMask) {
        int firstSolid = -1;
        int lastSolid = -1;
        for (int i = 0; i < solidMask.length; i++) {
            if (!solidMask[i]) {
                continue;
            }
            if (firstSolid < 0) {
                firstSolid = i;
            }
            lastSolid = i;
        }
        if (firstSolid < 0) {
            return 0;
        }
        for (int i = firstSolid; i <= lastSolid; i++) {
            solidMask[i] = true;
        }
        return lastSolid - firstSolid + 1;
    }

    private static int highestSolidIndex(boolean[] solidMask) {
        for (int i = solidMask.length - 1; i >= 0; i--) {
            if (solidMask[i]) {
                return i;
            }
        }
        return -1;
    }

    private static int computeTopHeight(IrisFloatingChildBiomes entry, IrisBiome target, Engine engine, long baseSeed, int wx, int wz, IrisData data) {
        int maxTopHeight = Math.max(0, entry.getMaxTopHeight());
        if (maxTopHeight == 0) {
            return 0;
        }
        return switch (entry.getTopShapeMode()) {
            case FLAT -> maxTopHeight;
            case NOISE -> {
                CNG topCng = entry.getTopShapeCng(baseSeed, data);
                if (topCng == null) {
                    warnNullCng("topShapeStyle", null);
                    yield maxTopHeight / 2;
                }
                double n = topCng.noise(wx, wz);
                double clamped = Math.max(0, Math.min(1, n));
                double amp = Math.max(0, Math.min(1, entry.getTopShapeAmp()));
                yield (int) Math.round(clamped * amp * maxTopHeight);
            }
            case BIOME -> {
                if (target == null) {
                    yield maxTopHeight / 2;
                }
                double h = target.getHeight(engine, wx, wz, baseSeed);
                int rounded = (int) Math.round(h);
                if (rounded < 0) {
                    yield 0;
                }
                yield Math.min(maxTopHeight, rounded);
            }
        };
    }
}
