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

import art.arcane.iris.Iris;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisDirection;
import art.arcane.iris.engine.object.IrisJigsawConnector;
import art.arcane.iris.engine.object.IrisJigsawPiece;
import art.arcane.iris.engine.object.IrisJigsawPieceEntry;
import art.arcane.iris.engine.object.IrisJigsawPool;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.JigsawJoint;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.util.BlockVector;

import java.util.ArrayDeque;
import java.util.Deque;

public final class StructureAssembler {
    private static final int HARD_PIECE_CAP = 512;
    private static final int[] Y_DEGREES = {0, 90, 180, 270};

    private final IrisData data;
    private final IrisStructure structure;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int radius;

    public StructureAssembler(IrisData data, IrisStructure structure, int originX, int originY, int originZ) {
        this.data = data;
        this.structure = structure;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.radius = Math.max(1, structure.getMaxSizeChunks()) * 16;
    }

    public KList<PlacedStructurePiece> assemble(RNG rng) {
        IrisJigsawPool startPool = IrisData.loadAnyJigsawPool(structure.getStartPool(), data);
        if (startPool == null || startPool.getPieces().isEmpty()) {
            Iris.warn("Structure " + structure.getLoadKey() + " has no resolvable start pool '" + structure.getStartPool() + "'");
            return null;
        }

        KList<PlacedStructurePiece> placed = new KList<>();
        Deque<OpenConnector> open = new ArrayDeque<>();

        IrisJigsawPiece startPiece = pickPiece(startPool, rng);
        if (startPiece == null) {
            return null;
        }
        IrisObject startObject = IrisData.loadAnyObject(startPiece.getObject(), data);
        if (startObject == null) {
            Iris.warn("Jigsaw piece references missing object '" + startPiece.getObject() + "'");
            return null;
        }

        int startRotY = startPiece.isRotatable() ? Y_DEGREES[rng.i(0, 3)] : 0;
        IrisObjectRotation startRot = IrisObjectRotation.of(0, startRotY, 0);
        PlacedStructurePiece start = build(startPiece, startObject, originX, originY, originZ, startRot);
        placed.add(start);
        enqueueConnectors(open, start, startObject, 1, null);

        while (!open.isEmpty() && placed.size() < HARD_PIECE_CAP) {
            OpenConnector c = open.poll();
            String poolName = c.pool;
            int depth = c.depth;

            IrisJigsawPool pool = IrisData.loadAnyJigsawPool(poolName, data);
            if (pool == null) {
                continue;
            }
            if (depth > structure.getMaxDepth()) {
                if (pool.getFallback() == null || pool.getFallback().isEmpty()) {
                    continue;
                }
                pool = IrisData.loadAnyJigsawPool(pool.getFallback(), data);
                if (pool == null || pool.getPieces().isEmpty()) {
                    continue;
                }
            }

            attachOne(open, placed, pool, c, depth, rng);
        }

        return placed;
    }

    private void attachOne(Deque<OpenConnector> open, KList<PlacedStructurePiece> placed, IrisJigsawPool pool, OpenConnector c, int depth, RNG rng) {
        for (String pieceName : weightedOrder(pool, rng)) {
            IrisJigsawPiece piece = IrisData.loadAnyJigsawPiece(pieceName, data);
            if (piece == null) {
                continue;
            }
            IrisObject object = IrisData.loadAnyObject(piece.getObject(), data);
            if (object == null) {
                continue;
            }

            for (IrisJigsawConnector cb : piece.getConnectors()) {
                if (!cb.getName().equals(c.targetName)) {
                    continue;
                }

                IrisDirection needed = c.facing.reverse();
                for (int yDeg : rotationCandidates(cb.getJoint(), rng)) {
                    IrisObjectRotation rot = IrisObjectRotation.of(0, yDeg, 0);
                    IrisDirection rotatedFace = rotateDirection(rot, cb.getDirection());
                    if (rotatedFace != needed) {
                        continue;
                    }

                    BlockVector centerB = centerOf(object);
                    BlockVector crB = new BlockVector(
                            cb.getPosition().getX() - centerB.getBlockX(),
                            cb.getPosition().getY() - centerB.getBlockY(),
                            cb.getPosition().getZ() - centerB.getBlockZ());
                    BlockVector rcrB = rot.rotate(crB.clone(), 0, 0, 0);
                    int wcx = c.wx + c.facing.toVector().getBlockX();
                    int wcy = c.wy + c.facing.toVector().getBlockY();
                    int wcz = c.wz + c.facing.toVector().getBlockZ();
                    int px = wcx - rcrB.getBlockX();
                    int py = wcy - rcrB.getBlockY();
                    int pz = wcz - rcrB.getBlockZ();

                    PlacedStructurePiece candidate = build(piece, object, px, py, pz, rot);
                    if (!withinRadius(candidate) || collides(placed, candidate)) {
                        continue;
                    }

                    placed.add(candidate);
                    enqueueConnectors(open, candidate, object, depth + 1, cb);
                    return;
                }
            }
        }
    }

    private void enqueueConnectors(Deque<OpenConnector> open, PlacedStructurePiece p, IrisObject object, int depth, IrisJigsawConnector skip) {
        BlockVector center = centerOf(object);
        for (IrisJigsawConnector con : p.getPiece().getConnectors()) {
            if (con == skip) {
                continue;
            }
            BlockVector cr = new BlockVector(
                    con.getPosition().getX() - center.getBlockX(),
                    con.getPosition().getY() - center.getBlockY(),
                    con.getPosition().getZ() - center.getBlockZ());
            BlockVector rcr = p.getRotation().rotate(cr.clone(), 0, 0, 0);
            IrisDirection facing = rotateDirection(p.getRotation(), con.getDirection());
            open.add(new OpenConnector(
                    p.getX() + rcr.getBlockX(),
                    p.getY() + rcr.getBlockY(),
                    p.getZ() + rcr.getBlockZ(),
                    facing, con.getPool(), con.getName(), con.getTargetName(), con.getJoint(), depth));
        }
    }

    private PlacedStructurePiece build(IrisJigsawPiece piece, IrisObject object, int x, int y, int z, IrisObjectRotation rot) {
        BlockVector rotated = rot.rotate(new BlockVector(object.getW(), object.getH(), object.getD()), 0, 0, 0);
        int rw = Math.abs(rotated.getBlockX());
        int rh = Math.abs(rotated.getBlockY());
        int rd = Math.abs(rotated.getBlockZ());
        int hx = rw / 2;
        int hy = rh / 2;
        int hz = rd / 2;
        return new PlacedStructurePiece(piece, object, x, y, z, rot,
                x - hx, y - hy, z - hz, x + hx, y + hy, z + hz);
    }

    private boolean withinRadius(PlacedStructurePiece p) {
        return p.getMinX() >= originX - radius && p.getMaxX() <= originX + radius
                && p.getMinZ() >= originZ - radius && p.getMaxZ() <= originZ + radius;
    }

    private boolean collides(KList<PlacedStructurePiece> placed, PlacedStructurePiece candidate) {
        for (PlacedStructurePiece p : placed) {
            if (p.intersects(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int[] rotationCandidates(JigsawJoint joint, RNG rng) {
        if (joint == JigsawJoint.ALIGNED) {
            return Y_DEGREES;
        }
        int[] shuffled = {0, 90, 180, 270};
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = rng.i(0, i);
            int t = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = t;
        }
        return shuffled;
    }

    private IrisDirection rotateDirection(IrisObjectRotation rot, IrisDirection direction) {
        BlockVector v = direction.toVector().toBlockVector();
        BlockVector r = rot.rotate(v.clone(), 0, 0, 0);
        return IrisDirection.getDirection(r);
    }

    private BlockVector centerOf(IrisObject object) {
        return new BlockVector(object.getW() / 2, object.getH() / 2, object.getD() / 2);
    }

    private IrisJigsawPiece pickPiece(IrisJigsawPool pool, RNG rng) {
        String name = weightedPick(pool, rng);
        return name == null ? null : IrisData.loadAnyJigsawPiece(name, data);
    }

    private String weightedPick(IrisJigsawPool pool, RNG rng) {
        int total = 0;
        for (IrisJigsawPieceEntry e : pool.getPieces()) {
            total += Math.max(1, e.getWeight());
        }
        if (total <= 0) {
            return null;
        }
        int t = rng.i(0, total - 1);
        for (IrisJigsawPieceEntry e : pool.getPieces()) {
            t -= Math.max(1, e.getWeight());
            if (t < 0) {
                return e.getPiece();
            }
        }
        return pool.getPieces().getFirst().getPiece();
    }

    private KList<String> weightedOrder(IrisJigsawPool pool, RNG rng) {
        KList<IrisJigsawPieceEntry> remaining = new KList<>(pool.getPieces());
        KList<String> order = new KList<>();
        while (!remaining.isEmpty()) {
            int total = 0;
            for (IrisJigsawPieceEntry e : remaining) {
                total += Math.max(1, e.getWeight());
            }
            int t = rng.i(0, total - 1);
            int idx = 0;
            for (int i = 0; i < remaining.size(); i++) {
                t -= Math.max(1, remaining.get(i).getWeight());
                if (t < 0) {
                    idx = i;
                    break;
                }
            }
            order.add(remaining.remove(idx).getPiece());
        }
        return order;
    }

    private record OpenConnector(int wx, int wy, int wz, IrisDirection facing, String pool, String name,
                                 String targetName, JigsawJoint joint, int depth) {
    }
}
