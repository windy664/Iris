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

package art.arcane.iris.core.tools;

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.util.common.data.VectorMap;
import org.bukkit.Axis;
import org.bukkit.Tag;
import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.util.BlockVector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TreePlausibilizer {
    public static final int MAX_DISTANCE = 6;
    public static final int DEFAULT_SHELL_RADIUS = 2;
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };
    private static final BlockData FALLBACK_LOG = BukkitBlockResolution.get("minecraft:oak_log[axis=y]");
    private static final BlockData FALLBACK_LEAF = BukkitBlockResolution.get("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]");

    private TreePlausibilizer() {
    }

    public record Result(
            int totalLeaves,
            int persistentLeavesInput,
            int reachableBefore,
            int logsAdded,
            int leavesAdded,
            int leavesRemoved,
            int leavesNormalized,
            int unreachableAfter,
            String skipReason
    ) {
        public static Result skipped(String reason) {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0, reason);
        }
    }

    public static Result analyze(IrisObject obj, PlausibilizeMode mode, int shellRadius) {
        return run(obj, false, mode, shellRadius);
    }

    public static Result apply(IrisObject obj, PlausibilizeMode mode, int shellRadius) {
        return run(obj, true, mode, shellRadius);
    }

    private static Result run(IrisObject obj, boolean mutate, PlausibilizeMode mode, int shellRadius) {
        boolean normalize = mode == PlausibilizeMode.NORMALIZE;
        boolean smoke = mode == PlausibilizeMode.SMOKE;
        boolean foliageOverature = mode == PlausibilizeMode.FOLIAGE_OVERATURE;
        VectorMap<PlatformBlockState> blocks = obj.getBlocks();
        Map<Long, BlockData> positions = new HashMap<>(blocks.size() * 2);
        Set<Long> logPositions = new HashSet<>();
        Set<Long> originalLeafPositions = new HashSet<>();
        Set<Long> persistentLeafPositions = new HashSet<>();
        Map<BlockData, Integer> leafTypeCounts = new HashMap<>();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Map.Entry<BlockVector, PlatformBlockState> entry : blocks) {
            BlockVector pos = entry.getKey();
            BlockData data = (BlockData) entry.getValue().nativeHandle();
            long key = packKey(pos);
            positions.put(key, data);
            int x = pos.getBlockX();
            int y = pos.getBlockY();
            int z = pos.getBlockZ();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
            if (isLog(data)) {
                logPositions.add(key);
                continue;
            }
            if (isLeaf(data)) {
                boolean persistent = data instanceof Leaves leaves && leaves.isPersistent();
                if (persistent) {
                    persistentLeafPositions.add(key);
                }
                originalLeafPositions.add(key);
                leafTypeCounts.merge(data, 1, Integer::sum);
            }
        }

        int persistentInput = persistentLeafPositions.size();
        int totalLeavesInitial = originalLeafPositions.size();

        if (logPositions.isEmpty() && !originalLeafPositions.isEmpty()) {
            return Result.skipped("leaves present but no logs to bridge from");
        }
        if (logPositions.isEmpty() && !smoke) {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        Set<Long> leafPositions;
        int leavesRemoved = 0;
        List<Long> removedLeafKeys = new ArrayList<>();

        if (smoke) {
            leafPositions = new HashSet<>();
            for (long key : originalLeafPositions) {
                removedLeafKeys.add(key);
                positions.remove(key);
            }
            leavesRemoved = removedLeafKeys.size();
            int r = Math.max(0, Math.min(shellRadius, 5));
            BlockData leafTemplate = pickDominantLeaf(leafTypeCounts);
            paintShell(
                    logPositions, positions, leafPositions,
                    leafTemplate, r,
                    minX, minY, minZ, maxX, maxY, maxZ
            );
        } else if (normalize) {
            leafPositions = new HashSet<>(originalLeafPositions);
        } else {
            leafPositions = new HashSet<>(originalLeafPositions);
            leafPositions.removeAll(persistentLeafPositions);
        }

        int reachableBefore;
        int logsAdded = 0;
        Set<Long> unreached;
        Map<Long, Integer> distances;
        List<LogInsertion> inserts = new ArrayList<>();
        Set<Long> orphanRemovals = new HashSet<>();
        List<LeafAddition> leafAdds = new ArrayList<>();

        if (!leafPositions.isEmpty() && !logPositions.isEmpty()) {
            Set<Long> connectivityLeaves;
            if (!normalize && !smoke) {
                connectivityLeaves = new HashSet<>(leafPositions);
                connectivityLeaves.addAll(persistentLeafPositions);
            } else {
                connectivityLeaves = leafPositions;
            }

            distances = seedDistances(logPositions, connectivityLeaves);
            reachableBefore = countReachable(leafPositions, distances);
            unreached = new HashSet<>(leafPositions);
            unreached.removeAll(distances.keySet());

            if (foliageOverature && !smoke && !unreached.isEmpty()) {
                BlockData bridgeLeaf = pickDominantLeaf(leafTypeCounts);
                foliageBridge(
                        unreached, logPositions, distances,
                        leafPositions, connectivityLeaves, positions,
                        bridgeLeaf, leafAdds,
                        minX, minY, minZ, maxX, maxY, maxZ
                );
                distances = seedDistances(logPositions, connectivityLeaves);
                unreached = new HashSet<>(leafPositions);
                unreached.removeAll(distances.keySet());
            }

            logsAdded = tentacleGrow(
                    unreached, distances,
                    logPositions, leafPositions, connectivityLeaves, positions,
                    inserts, orphanRemovals, !foliageOverature
            );
        } else {
            distances = new HashMap<>();
            unreached = new HashSet<>();
            reachableBefore = 0;
        }

        leavesRemoved += orphanRemovals.size();

        int leavesAdded = leafAdds.size();
        if (smoke) {
            BlockData leafTemplate = pickDominantLeaf(leafTypeCounts);
            for (long key : leafPositions) {
                leafAdds.add(new LeafAddition(key, leafTemplate));
            }
            leavesAdded = leafAdds.size();
        }

        int leavesNormalized = 0;
        List<LeafRewrite> normalizeRewrites = new ArrayList<>();
        if (normalize && !smoke) {
            for (long pos : leafPositions) {
                BlockData data = positions.get(pos);
                if (data instanceof Leaves leaves && leaves.isPersistent()) {
                    BlockData cloned = data.clone();
                    ((Leaves) cloned).setPersistent(false);
                    normalizeRewrites.add(new LeafRewrite(pos, cloned));
                    leavesNormalized++;
                }
            }
        }

        if (mutate) {
            if (smoke) {
                for (long key : removedLeafKeys) {
                    if (!positions.containsKey(key)) {
                        blocks.remove(unpackKey(key));
                    }
                }
            }
            for (long key : orphanRemovals) {
                blocks.remove(unpackKey(key));
            }
            for (LeafAddition addition : leafAdds) {
                blocks.put(unpackKey(addition.key()), BukkitBlockState.of(addition.data()));
            }
            for (LogInsertion insertion : inserts) {
                blocks.put(unpackKey(insertion.key()), BukkitBlockState.of(insertion.data()));
            }
            for (LeafRewrite rewrite : normalizeRewrites) {
                blocks.put(unpackKey(rewrite.key()), BukkitBlockState.of(rewrite.data()));
            }
        }

        int finalLeafCount = leafPositions.size();
        if (!normalize && !smoke) {
            finalLeafCount += persistentInput;
        }

        return new Result(
                smoke ? leavesAdded : totalLeavesInitial,
                persistentInput,
                reachableBefore,
                logsAdded,
                leavesAdded,
                leavesRemoved,
                leavesNormalized,
                unreached.size(),
                null
        );
    }

    private static void paintShell(
            Set<Long> logPositions,
            Map<Long, BlockData> positions,
            Set<Long> leafPositions,
            BlockData leafTemplate,
            int radius,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        if (radius <= 0) {
            return;
        }
        int r2 = radius * radius;
        int bxMin = minX;
        int byMin = minY;
        int bzMin = minZ;
        int bxMax = maxX;
        int byMax = maxY;
        int bzMax = maxZ;

        for (long log : logPositions) {
            int[] lx = unpack(log);
            for (int dx = -radius; dx <= radius; dx++) {
                int ax = lx[0] + dx;
                if (ax < bxMin || ax > bxMax) continue;
                int dx2 = dx * dx;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ay = lx[1] + dy;
                    if (ay < byMin || ay > byMax) continue;
                    int dy2 = dy * dy;
                    int partial = dx2 + dy2;
                    if (partial > r2) continue;
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (partial + dz * dz > r2) continue;
                        int az = lx[2] + dz;
                        if (az < bzMin || az > bzMax) continue;
                        long nk = packXYZ(ax, ay, az);
                        if (logPositions.contains(nk)) continue;
                        if (positions.containsKey(nk)) continue;
                        positions.put(nk, leafTemplate);
                        leafPositions.add(nk);
                    }
                }
            }
        }
    }

    private static int tentacleGrow(
            Set<Long> unreached,
            Map<Long, Integer> distances,
            Set<Long> logPositions,
            Set<Long> leafPositions,
            Set<Long> connectivityLeaves,
            Map<Long, BlockData> positions,
            List<LogInsertion> inserts,
            Set<Long> orphanRemovals,
            boolean deleteOrphans
    ) {
        int logsAdded = 0;
        int safetyLimit = unreached.size() * 2 + 32;
        long currentTarget = -1L;

        while (!unreached.isEmpty() && logsAdded < safetyLimit) {
            if (currentTarget == -1L || !unreached.contains(currentTarget)) {
                currentTarget = unreached.iterator().next();
            }

            long extensionLeaf = findWoodAdjacentLeafFrom(currentTarget, connectivityLeaves, logPositions);

            if (extensionLeaf == -1L) {
                if (deleteOrphans) {
                    removeOrphanCluster(currentTarget, connectivityLeaves, leafPositions, unreached, distances, positions, orphanRemovals);
                } else {
                    skipOrphanCluster(currentTarget, unreached, connectivityLeaves);
                }
                currentTarget = -1L;
                continue;
            }

            BlockData logData = pickLogVariant(extensionLeaf, positions, logPositions);
            inserts.add(new LogInsertion(extensionLeaf, logData));

            logPositions.add(extensionLeaf);
            leafPositions.remove(extensionLeaf);
            connectivityLeaves.remove(extensionLeaf);
            distances.remove(extensionLeaf);
            unreached.remove(extensionLeaf);
            positions.put(extensionLeaf, logData);
            logsAdded++;

            propagateFromLog(extensionLeaf, distances, connectivityLeaves, unreached);
        }
        return logsAdded;
    }

    private static long findWoodAdjacentLeafFrom(long start, Set<Long> leafPositions, Set<Long> logPositions) {
        if (!leafPositions.contains(start)) return -1L;
        if (hasLogNeighbor(start, logPositions)) return start;

        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int[] xyz = unpack(pos);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                if (!leafPositions.contains(nk) || !visited.add(nk)) continue;
                if (hasLogNeighbor(nk, logPositions)) return nk;
                queue.add(nk);
            }
        }
        return -1L;
    }

    private static boolean hasLogNeighbor(long key, Set<Long> logPositions) {
        int[] xyz = unpack(key);
        for (int[] n : NEIGHBORS) {
            long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
            if (logPositions.contains(nk)) return true;
        }
        return false;
    }

    private static void propagateFromLog(
            long logKey, Map<Long, Integer> distances,
            Set<Long> connectivityLeaves, Set<Long> unreached
    ) {
        int[] cx = unpack(logKey);
        Deque<Long> q = new ArrayDeque<>();
        for (int[] n : NEIGHBORS) {
            long nk = packXYZ(cx[0] + n[0], cx[1] + n[1], cx[2] + n[2]);
            if (connectivityLeaves.contains(nk)) {
                Integer cur = distances.get(nk);
                if (cur == null || cur > 1) {
                    if (cur == null) unreached.remove(nk);
                    distances.put(nk, 1);
                    q.add(nk);
                }
            }
        }
        while (!q.isEmpty()) {
            long pos = q.poll();
            int d = distances.get(pos);
            if (d >= MAX_DISTANCE) continue;
            int[] px = unpack(pos);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(px[0] + n[0], px[1] + n[1], px[2] + n[2]);
                if (connectivityLeaves.contains(nk)) {
                    Integer cur = distances.get(nk);
                    if (cur == null || cur > d + 1) {
                        if (cur == null) unreached.remove(nk);
                        distances.put(nk, d + 1);
                        q.add(nk);
                    }
                }
            }
        }
    }

    private static void foliageBridge(
            Set<Long> unreached,
            Set<Long> logPositions,
            Map<Long, Integer> distances,
            Set<Long> leafPositions,
            Set<Long> connectivityLeaves,
            Map<Long, BlockData> positions,
            BlockData leafTemplate,
            List<LeafAddition> leafAdds,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        Set<Long> pending = new HashSet<>(unreached);
        while (!pending.isEmpty()) {
            long seed = pending.iterator().next();
            Set<Long> cluster = new HashSet<>();
            Deque<Long> cq = new ArrayDeque<>();
            cq.add(seed);
            cluster.add(seed);
            while (!cq.isEmpty()) {
                long p = cq.poll();
                int[] xyz = unpack(p);
                for (int[] n : NEIGHBORS) {
                    long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                    if (pending.contains(nk) && cluster.add(nk)) {
                        cq.add(nk);
                    }
                }
            }

            Map<Long, Long> parent = new HashMap<>();
            Deque<Long> q = new ArrayDeque<>();
            for (long c : cluster) {
                parent.put(c, -1L);
                q.add(c);
            }

            long pathEnd = -1L;
            while (!q.isEmpty() && pathEnd == -1L) {
                long p = q.poll();
                int[] xyz = unpack(p);
                for (int[] n : NEIGHBORS) {
                    int nx = xyz[0] + n[0];
                    int ny = xyz[1] + n[1];
                    int nz = xyz[2] + n[2];
                    if (nx < minX || nx > maxX) continue;
                    if (ny < minY || ny > maxY) continue;
                    if (nz < minZ || nz > maxZ) continue;
                    long nk = packXYZ(nx, ny, nz);
                    if (parent.containsKey(nk)) continue;
                    if (logPositions.contains(nk) || distances.containsKey(nk)) {
                        pathEnd = p;
                        break;
                    }
                    if (positions.containsKey(nk)) continue;
                    parent.put(nk, p);
                    q.add(nk);
                }
            }

            pending.removeAll(cluster);

            if (pathEnd == -1L) {
                continue;
            }

            long cur = pathEnd;
            while (cur != -1L && !cluster.contains(cur)) {
                if (!positions.containsKey(cur)) {
                    BlockData clone = leafTemplate.clone();
                    positions.put(cur, clone);
                    leafPositions.add(cur);
                    connectivityLeaves.add(cur);
                    leafAdds.add(new LeafAddition(cur, clone));
                }
                cur = parent.get(cur);
            }
        }
    }

    private static void skipOrphanCluster(long seed, Set<Long> unreached, Set<Long> connectivityLeaves) {
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(seed);
        visited.add(seed);
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            unreached.remove(pos);
            int[] xyz = unpack(pos);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                if (visited.add(nk) && unreached.contains(nk) && connectivityLeaves.contains(nk)) {
                    queue.add(nk);
                }
            }
        }
    }

    private static void removeOrphanCluster(
            long seed,
            Set<Long> connectivityLeaves, Set<Long> leafPositions, Set<Long> unreached,
            Map<Long, Integer> distances, Map<Long, BlockData> positions, Set<Long> orphanRemovals
    ) {
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int[] xyz = unpack(pos);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                if (visited.add(nk) && connectivityLeaves.contains(nk)) {
                    queue.add(nk);
                }
            }
            orphanRemovals.add(pos);
            connectivityLeaves.remove(pos);
            leafPositions.remove(pos);
            unreached.remove(pos);
            distances.remove(pos);
            positions.remove(pos);
        }
    }

    private static Map<Long, Integer> seedDistances(Set<Long> logPositions, Set<Long> leafPositions) {
        Map<Long, Integer> dist = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();

        for (long leaf : leafPositions) {
            int[] xyz = unpack(leaf);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                if (logPositions.contains(nk)) {
                    dist.put(leaf, 1);
                    queue.add(leaf);
                    break;
                }
            }
        }

        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int d = dist.get(pos);
            if (d >= MAX_DISTANCE) {
                continue;
            }
            int[] xyz = unpack(pos);
            for (int[] n : NEIGHBORS) {
                long nk = packXYZ(xyz[0] + n[0], xyz[1] + n[1], xyz[2] + n[2]);
                if (leafPositions.contains(nk) && !dist.containsKey(nk)) {
                    dist.put(nk, d + 1);
                    queue.add(nk);
                }
            }
        }

        return dist;
    }

    private static int countReachable(Set<Long> leafPositions, Map<Long, Integer> distances) {
        int count = 0;
        for (long leaf : leafPositions) {
            if (distances.containsKey(leaf)) {
                count++;
            }
        }
        return count;
    }

    private static BlockData pickDominantLeaf(Map<BlockData, Integer> leafTypeCounts) {
        BlockData best = null;
        int bestCount = -1;
        for (Map.Entry<BlockData, Integer> e : leafTypeCounts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return FALLBACK_LEAF.clone();
        }
        BlockData clone = best.clone();
        if (clone instanceof Leaves leaves) {
            leaves.setPersistent(false);
        }
        return clone;
    }

    private static BlockData pickLogVariant(long target, Map<Long, BlockData> positions, Set<Long> logPositions) {
        if (logPositions.isEmpty()) {
            return FALLBACK_LOG.clone();
        }
        int[] tx = unpack(target);
        long nearest = -1L;
        long nearestDistSq = Long.MAX_VALUE;
        for (long lp : logPositions) {
            int[] lx = unpack(lp);
            long dx = tx[0] - lx[0];
            long dy = tx[1] - lx[1];
            long dz = tx[2] - lx[2];
            long d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < nearestDistSq) {
                nearestDistSq = d2;
                nearest = lp;
            }
        }
        BlockData source = positions.get(nearest);
        if (source == null) {
            return FALLBACK_LOG.clone();
        }
        BlockData clone = source.clone();
        if (clone instanceof Orientable orientable) {
            orientable.setAxis(Axis.Y);
        }
        return clone;
    }

    private static boolean isLog(BlockData data) {
        return Tag.LOGS.isTagged(data.getMaterial());
    }

    private static boolean isLeaf(BlockData data) {
        return Tag.LEAVES.isTagged(data.getMaterial()) || data instanceof Leaves;
    }

    private static long packKey(BlockVector pos) {
        return packXYZ(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
    }

    private static long packXYZ(int x, int y, int z) {
        long lx = (x + 32768L) & 0xFFFFL;
        long ly = (y + 32768L) & 0xFFFFL;
        long lz = (z + 32768L) & 0xFFFFL;
        return (lx << 32) | (ly << 16) | lz;
    }

    private static int[] unpack(long key) {
        int x = (int) ((key >> 32) & 0xFFFFL) - 32768;
        int y = (int) ((key >> 16) & 0xFFFFL) - 32768;
        int z = (int) (key & 0xFFFFL) - 32768;
        return new int[]{x, y, z};
    }

    private static BlockVector unpackKey(long key) {
        int[] xyz = unpack(key);
        return new BlockVector(xyz[0], xyz[1], xyz[2]);
    }

    private record LogInsertion(long key, BlockData data) {
    }

    private record LeafAddition(long key, BlockData data) {
    }

    private record LeafRewrite(long key, BlockData data) {
    }
}
