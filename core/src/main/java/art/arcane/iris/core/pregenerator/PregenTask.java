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

package art.arcane.iris.core.pregenerator;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.math.PowerOfTwoCoordinates;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.math.Spiraled;
import art.arcane.volmlib.util.math.Spiraler;
import lombok.Builder;
import lombok.Data;

import java.util.Comparator;

@Builder
@Data
public class PregenTask {
    private static final KMap<Long, int[]> ORDERS = new KMap<>();

    @Builder.Default
    private final boolean gui = false;
    @Builder.Default
    private final Position2 center = new Position2(0, 0);
    @Builder.Default
    private final int radiusX = 1;
    @Builder.Default
    private final int radiusZ = 1;

    private final Bounds bounds = new Bounds();

    protected PregenTask(boolean gui, Position2 center, int radiusX, int radiusZ) {
        this.gui = gui;
        this.center = new ProxiedPos(center);
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        bounds.update();
    }

    public static void iterateRegion(int xr, int zr, Spiraled s, Position2 pull) {
        iterateRegion(xr, zr, s, pull.getX(), pull.getZ());
    }

    public static void iterateRegion(int xr, int zr, Spiraled s, int pullX, int pullZ) {
        for (int packed : orderForPull(pullX, pullZ)) {
            s.on(PowerOfTwoCoordinates.unpackLocal32X(packed) + PowerOfTwoCoordinates.regionToChunk(xr), PowerOfTwoCoordinates.unpackLocal32Z(packed) + PowerOfTwoCoordinates.regionToChunk(zr));
        }
    }

    public static void iterateRegion(int xr, int zr, Spiraled s) {
        iterateRegion(xr, zr, s, -PowerOfTwoCoordinates.regionToChunk(xr), -PowerOfTwoCoordinates.regionToChunk(zr));
    }

    private static int[] orderForPull(int pullX, int pullZ) {
        long key = orderKey(pullX, pullZ);
        return ORDERS.computeIfAbsent(key, PregenTask::computeOrder);
    }

    private static int[] computeOrder(long key) {
        int pullX = (int) (key >> 32);
        int pullZ = (int) key;
        Position2 pull = new Position2(pullX, pullZ);
        KList<Position2> p = new KList<>();
        new Spiraler(33, 33, (x, z) -> {
            int xx = (x + 15);
            int zz = (z + 15);
            if (xx < 0 || xx > 31 || zz < 0 || zz > 31) {
                return;
            }

            p.add(new Position2(xx, zz));
        }).drain();
        p.sort(Comparator.comparing((i) -> i.distance(pull)));

        int[] packed = new int[p.size()];
        for (int index = 0; index < p.size(); index++) {
            Position2 position = p.get(index);
            packed[index] = PowerOfTwoCoordinates.packLocal32(position.getX(), position.getZ());
        }

        return packed;
    }

    private static long orderKey(int pullX, int pullZ) {
        long high = (long) pullX << 32;
        long low = pullZ & 0xFFFFFFFFL;
        return high | low;
    }

    public void iterateRegions(Spiraled s) {
        Bound bound = bounds.region();
        new Spiraler(bound.sizeX, bound.sizeZ, ((x, z) -> {
            if (bound.check(x, z)) s.on(x, z);
        })).setOffset(PowerOfTwoCoordinates.blockToRegionFloor(center.getX()), PowerOfTwoCoordinates.blockToRegionFloor(center.getZ())).drain();
    }

    public void iterateChunks(int rX, int rZ, Spiraled s) {
        Bound bound = bounds.chunk();
        iterateRegion(rX, rZ, ((x, z) -> {
            if (bound.check(x, z)) s.on(x, z);
        }));
    }

    public void iterateAllChunks(Spiraled s) {
        iterateRegions(((rX, rZ) -> iterateChunks(rX, rZ, s)));
    }

    private class Bounds {
        private Bound chunk = null;
        private Bound region = null;

        public void update() {
            int maxX = center.getX() + radiusX;
            int maxZ = center.getZ() + radiusZ;
            int minX = center.getX() - radiusX;
            int minZ = center.getZ() - radiusZ;

            chunk = new Bound(
                    PowerOfTwoCoordinates.blockToChunkFloor(minX),
                    PowerOfTwoCoordinates.blockToChunkFloor(minZ),
                    PowerOfTwoCoordinates.ceilDivPow2(maxX, PowerOfTwoCoordinates.CHUNK_BITS),
                    PowerOfTwoCoordinates.ceilDivPow2(maxZ, PowerOfTwoCoordinates.CHUNK_BITS)
            );
            region = new Bound(
                    PowerOfTwoCoordinates.blockToRegionFloor(minX),
                    PowerOfTwoCoordinates.blockToRegionFloor(minZ),
                    PowerOfTwoCoordinates.ceilDivPow2(maxX, PowerOfTwoCoordinates.REGION_BITS),
                    PowerOfTwoCoordinates.ceilDivPow2(maxZ, PowerOfTwoCoordinates.REGION_BITS)
            );
        }

        public Bound chunk() {
            if (chunk == null) update();
            return chunk;
        }

        public Bound region() {
            if (region == null) update();
            return region;
        }
    }

    private record Bound(int minX, int minZ, int maxX, int maxZ, int sizeX, int sizeZ) {
        private Bound(int minX, int minZ, int maxX, int maxZ) {
            this(minX, minZ, maxX, maxZ, maxX - minX + 1, maxZ - minZ + 1);
        }

        boolean check(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static class ProxiedPos extends Position2 {
        public ProxiedPos(Position2 p) {
            super(p.getX(), p.getZ());
        }

        @Override
        public void setX(int x) {
            throw new IllegalStateException("This Position2 may not be modified");
        }

        @Override
        public void setZ(int z) {
            throw new IllegalStateException("This Position2 may not be modified");
        }
    }
}
