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
import art.arcane.iris.util.common.data.B;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class FloatingObjectFootprint {
    private static final ConcurrentHashMap<String, FloatingObjectFootprint> CACHE = new ConcurrentHashMap<>();
    private static final boolean DIAGNOSTIC_LOG = Boolean.parseBoolean(System.getProperty("iris.floating.footprintLog", "true"));

    private final int lowestSolidKeyY;
    private final int highestSolidKeyY;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int tallestKx;
    private final int tallestKz;
    private final int tallestKxBottom;
    private final int tallestKzBottom;
    private final long[] footprintXZ;

    private FloatingObjectFootprint(int lowestSolidKeyY, int highestSolidKeyY, int centerX, int centerY, int centerZ, int tallestKx, int tallestKz, int tallestKxBottom, int tallestKzBottom, long[] footprintXZ) {
        this.lowestSolidKeyY = lowestSolidKeyY;
        this.highestSolidKeyY = highestSolidKeyY;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.tallestKx = tallestKx;
        this.tallestKz = tallestKz;
        this.tallestKxBottom = tallestKxBottom;
        this.tallestKzBottom = tallestKzBottom;
        this.footprintXZ = footprintXZ;
    }

    public static FloatingObjectFootprint compute(IrisObject obj) {
        String cacheKey = obj.getLoadKey() + "@" + obj.getW() + "x" + obj.getH() + "x" + obj.getD();
        return CACHE.computeIfAbsent(cacheKey, k -> doCompute(obj, k));
    }

    private static FloatingObjectFootprint doCompute(IrisObject obj, String cacheKey) {
        int cx = obj.getCenter().getBlockX();
        int cy = obj.getCenter().getBlockY();
        int cz = obj.getCenter().getBlockZ();
        Map<Long, int[]> columnStats = new HashMap<>();

        int[] globalHighestY = {Integer.MIN_VALUE};
        int[] globalHighestKx = {0};
        int[] globalHighestKz = {0};

        obj.getBlocks().forEach((BlockVector key, BlockData bd) -> {
            if (!B.isSolid(bd)) {
                return;
            }
            int kx = key.getBlockX();
            int ky = key.getBlockY();
            int kz = key.getBlockZ();
            long packed = ((long) kx << 32) | (kz & 0xFFFFFFFFL);
            int[] stats = columnStats.get(packed);
            if (stats == null) {
                stats = new int[]{ky, 1};
                columnStats.put(packed, stats);
            } else {
                if (ky < stats[0]) {
                    stats[0] = ky;
                }
                stats[1]++;
            }
            if (ky > globalHighestY[0]) {
                globalHighestY[0] = ky;
                globalHighestKx[0] = kx;
                globalHighestKz[0] = kz;
            }
        });

        long[] footprintArray = new long[columnStats.size()];
        int idx = 0;
        for (Long packed : columnStats.keySet()) {
            footprintArray[idx++] = packed;
        }

        long tallestPacked = resolveTallestColumn(columnStats);
        int lowestSolidKeyY = columnStats.isEmpty() ? cy : columnStats.get(tallestPacked)[0];
        int highestSolidKeyY = columnStats.isEmpty() ? cy : globalHighestY[0];
        int tallestKx = columnStats.isEmpty() ? 0 : (int) (tallestPacked >> 32);
        int tallestKz = columnStats.isEmpty() ? 0 : (int) (tallestPacked & 0xFFFFFFFFL);
        int tallestKxBottom = columnStats.isEmpty() ? 0 : globalHighestKx[0];
        int tallestKzBottom = columnStats.isEmpty() ? 0 : globalHighestKz[0];
        if (DIAGNOSTIC_LOG) {
            logFootprintDiagnostic(cacheKey, obj, cx, cy, cz, lowestSolidKeyY, tallestKx, tallestKz, columnStats);
        }
        return new FloatingObjectFootprint(lowestSolidKeyY, highestSolidKeyY, cx, cy, cz, tallestKx, tallestKz, tallestKxBottom, tallestKzBottom, footprintArray);
    }

    private static void logFootprintDiagnostic(String cacheKey, IrisObject obj, int cx, int cy, int cz, int anchorY, int tallestKx, int tallestKz, Map<Long, int[]> columnStats) {
        if (columnStats.isEmpty()) {
            Iris.info("[FloatingFootprint] key=" + cacheKey + " center=(" + cx + "," + cy + "," + cz + ") anchor=" + anchorY + " columns=0 (EMPTY)");
            return;
        }

        int tallestCount = 0;
        int tallestLow = Integer.MAX_VALUE;
        int floorLow = Integer.MAX_VALUE;
        int ceilingLow = Integer.MIN_VALUE;
        int minKx = Integer.MAX_VALUE, maxKx = Integer.MIN_VALUE;
        int minKz = Integer.MAX_VALUE, maxKz = Integer.MIN_VALUE;
        TreeMap<Integer, Integer> lowYHisto = new TreeMap<>();
        for (Map.Entry<Long, int[]> e : columnStats.entrySet()) {
            long packed = e.getKey();
            int kx = (int) (packed >> 32);
            int kz = (int) (packed & 0xFFFFFFFFL);
            if (kx < minKx) minKx = kx;
            if (kx > maxKx) maxKx = kx;
            if (kz < minKz) minKz = kz;
            if (kz > maxKz) maxKz = kz;
            int[] s = e.getValue();
            int count = s[1];
            int low = s[0];
            if (count > tallestCount || (count == tallestCount && low < tallestLow)) {
                tallestCount = count;
                tallestLow = low;
            }
            if (low < floorLow) floorLow = low;
            if (low > ceilingLow) ceilingLow = low;
            lowYHisto.merge(low, 1, Integer::sum);
        }

        int straysBelowAnchor = 0;
        for (int[] s : columnStats.values()) {
            if (s[0] < anchorY) straysBelowAnchor++;
        }

        List<Map.Entry<Long, int[]>> sorted = new ArrayList<>(columnStats.entrySet());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue()[1], a.getValue()[1]);
            if (cmp != 0) return cmp;
            return Integer.compare(a.getValue()[0], b.getValue()[0]);
        });
        StringBuilder topN = new StringBuilder();
        int showN = Math.min(4, sorted.size());
        for (int i = 0; i < showN; i++) {
            Map.Entry<Long, int[]> e = sorted.get(i);
            int kx = (int) (e.getKey() >> 32);
            int kz = (int) (e.getKey() & 0xFFFFFFFFL);
            int[] s = e.getValue();
            if (i > 0) topN.append(",");
            topN.append("(").append(kx).append(",").append(kz).append(")c=").append(s[1]).append(":y=").append(s[0]);
        }

        StringBuilder histo = new StringBuilder();
        int histoEntries = 0;
        for (Map.Entry<Integer, Integer> e : lowYHisto.entrySet()) {
            if (histoEntries++ > 0) histo.append(",");
            histo.append(e.getKey()).append("=").append(e.getValue());
            if (histoEntries >= 6) {
                if (lowYHisto.size() > 6) histo.append(",...+").append(lowYHisto.size() - 6);
                break;
            }
        }

        String keyStyle = (minKx >= 0 && minKz >= 0) ? "RAW" : "SIGNED";

        Iris.info("[FloatingFootprint] key=" + cacheKey
                + " dims=" + obj.getW() + "x" + obj.getH() + "x" + obj.getD()
                + " center=(" + cx + "," + cy + "," + cz + ")"
                + " keyStyle=" + keyStyle
                + " kxRange=[" + minKx + "," + maxKx + "]"
                + " kzRange=[" + minKz + "," + maxKz + "]"
                + " cols=" + columnStats.size()
                + " anchor=" + anchorY
                + " relAnchor=" + (anchorY - cy)
                + " tallestKxKz=(" + tallestKx + "," + tallestKz + ")"
                + " floorLow=" + floorLow
                + " ceilingLow=" + ceilingLow
                + " tallest=count" + tallestCount + ":y" + tallestLow
                + " straysBelow=" + straysBelowAnchor
                + " topCols=[" + topN + "]"
                + " lowYHisto={" + histo + "}");
    }

    private static long resolveTallestColumn(Map<Long, int[]> columnStats) {
        long bestPacked = 0L;
        int tallestCount = 0;
        int tallestLow = Integer.MAX_VALUE;
        for (Map.Entry<Long, int[]> e : columnStats.entrySet()) {
            int[] stats = e.getValue();
            int count = stats[1];
            int low = stats[0];
            if (count > tallestCount || (count == tallestCount && low < tallestLow)) {
                tallestCount = count;
                tallestLow = low;
                bestPacked = e.getKey();
            }
        }
        return bestPacked;
    }

    public int lowestSolidRelCenterY() {
        return lowestSolidKeyY - centerY;
    }

    public int getLowestSolidKeyY() {
        return lowestSolidKeyY;
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterY() {
        return centerY;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public int getTallestKx() {
        return tallestKx;
    }

    public int getTallestKz() {
        return tallestKz;
    }

    public int getHighestSolidKeyY() {
        return highestSolidKeyY;
    }

    public int getTallestKxBottom() {
        return tallestKxBottom;
    }

    public int getTallestKzBottom() {
        return tallestKzBottom;
    }

    public long[] footprintXZ() {
        return footprintXZ;
    }
}
