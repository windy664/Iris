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

package art.arcane.iris.engine.framework;

import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.math.RNG;

public final class StructurePlacementGrid {
    private StructurePlacementGrid() {
    }

    public static boolean startsInChunk(IrisStructurePlacement placement, int cx, int cz, long seed, RNG chunkRng) {
        return switch (placement.getDistribution()) {
            case RANDOM_SPREAD -> randomSpreadStart(cx, cz, placement.getSpacing(), placement.getSeparation(), placement.getSalt(), seed);
            case DENSITY -> chunkRng.chance(placement.getDensity());
            case CONCENTRIC_RINGS -> concentricRingsStart(cx, cz, placement, seed);
        };
    }

    public static boolean randomSpreadStart(int cx, int cz, int spacing, int separation, int salt, long seed) {
        int sp = Math.max(1, spacing);
        int sep = Math.max(0, Math.min(separation, sp - 1));
        int cellX = Math.floorDiv(cx, sp);
        int cellZ = Math.floorDiv(cz, sp);
        RNG r = new RNG(mix(seed, cellX, cellZ, salt));
        int range = Math.max(1, sp - sep);
        int startCx = cellX * sp + r.i(0, range - 1);
        int startCz = cellZ * sp + r.i(0, range - 1);
        return startCx == cx && startCz == cz;
    }

    private static boolean concentricRingsStart(int cx, int cz, IrisStructurePlacement placement, long seed) {
        if (cx == 0 && cz == 0) {
            return false;
        }
        int distance = Math.max(1, placement.getRingDistance());
        int count = Math.max(1, placement.getRingCount());
        int spread = Math.max(1, placement.getRingSpread());
        double dist = Math.sqrt((double) cx * cx + (double) cz * cz);
        int ring = (int) Math.round(dist / distance);
        if (ring <= 0) {
            return false;
        }
        int ringRadius = ring * distance;
        int slots = Math.min(spread * ring, count);
        if (slots <= 0) {
            return false;
        }
        double slotAngle = (2 * Math.PI) / slots;
        double offset = (mix(seed, ring, 0, 0) & 0xFFFFL) / 65536.0 * slotAngle;
        double angle = Math.atan2(cz, cx);
        int slotIndex = (int) Math.round((angle - offset) / slotAngle);
        double idealAngle = offset + slotIndex * slotAngle;
        int idealCx = (int) Math.round(Math.cos(idealAngle) * ringRadius);
        int idealCz = (int) Math.round(Math.sin(idealAngle) * ringRadius);
        return idealCx == cx && idealCz == cz;
    }

    public static long mix(long seed, int a, int b, int salt) {
        long h = seed;
        h = h * 6364136223846793005L + (a * 341873128712L);
        h = h * 6364136223846793005L + (b * 132897987541L);
        h = h * 6364136223846793005L + salt;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }
}
