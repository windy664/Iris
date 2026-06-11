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

package art.arcane.iris.engine.object.tree;

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisProceduralTree;
import art.arcane.iris.engine.object.IrisTreeDecorator;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.math.RNG;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;

public final class TreeBlockResolver {
    private TreeBlockResolver() {
    }

    public static BlockData resolve(IrisProceduralTree tree, IrisData data, TreeBlockCanvas.Cell cell, TreeBlockCanvas.Vec pos) {
        RNG paletteRng = new RNG(tree.getSeed());
        RNG posRng = new RNG(tree.getSeed() ^ positionHash(pos));

        switch (cell.role()) {
            case TRUNK -> {
                BlockData bd = resolveBlock(tree.getTrunk(), tree.getTrunkPalette(), data, pos, paletteRng);
                return finishTrunk(bd, cell);
            }
            case SECONDARY_TRUNK -> {
                BlockData bd = resolveBlock(tree.getSecondaryTrunk(), tree.getSecondaryTrunkPalette(), data, pos, paletteRng);
                if (bd == null) {
                    bd = resolveBlock(tree.getTrunk(), tree.getTrunkPalette(), data, pos, paletteRng);
                }
                return finishTrunk(bd, cell);
            }
            case LEAF -> {
                return resolveBlock(tree.getLeaves(), tree.getLeavesPalette(), data, pos, paletteRng);
            }
            case SECONDARY_LEAF -> {
                BlockData bd = resolveSecondaryLeaf(tree, data, pos, paletteRng, posRng);
                if (bd == null) {
                    bd = resolveBlock(tree.getLeaves(), tree.getLeavesPalette(), data, pos, paletteRng);
                }
                return bd;
            }
            case DECORATOR -> {
                int index = cell.decoratorIndex();
                if (index < 0 || tree.getDecorators() == null || index >= tree.getDecorators().size()) {
                    return null;
                }
                IrisTreeDecorator dec = tree.getDecorators().get(index);
                BlockData bd = resolveBlock(dec.getBlock(), dec.getPalette(), data, pos, paletteRng);
                if (bd != null && cell.facing() != null && bd instanceof Directional directional) {
                    try {
                        directional.setFacing(BlockFace.valueOf(cell.facing().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return bd;
            }
            default -> {
                return null;
            }
        }
    }

    private static BlockData finishTrunk(BlockData bd, TreeBlockCanvas.Cell cell) {
        if (bd == null) {
            return null;
        }
        if (cell.axis() != TreeBlockCanvas.Axis.NONE && bd instanceof Orientable orientable) {
            try {
                orientable.setAxis(Axis.valueOf(cell.axis().name()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (cell.exposed()) {
            return woodCap(bd);
        }
        return bd;
    }

    private static BlockData resolveSecondaryLeaf(IrisProceduralTree tree, IrisData data, TreeBlockCanvas.Vec pos, RNG paletteRng, RNG posRng) {
        if (TreeTrunkBuilder.paletteSet(tree.getSecondaryLeavesPalette())) {
            PlatformBlockState state = tree.getSecondaryLeavesPalette().get(paletteRng, pos.x(), pos.y(), pos.z(), data);
            return state == null ? null : ((BlockData) state.nativeHandle()).clone();
        }
        if (tree.getWeightedSecondaryLeaves() != null && !tree.getWeightedSecondaryLeaves().isEmpty()) {
            String picked = pickWeighted(tree, posRng);
            return picked == null ? null : cloneOrNull(BukkitBlockResolution.getOrNull(picked, false));
        }
        if (tree.getSecondaryLeaves() != null && !tree.getSecondaryLeaves().isEmpty()) {
            return cloneOrNull(BukkitBlockResolution.getOrNull(tree.getSecondaryLeaves(), false));
        }
        return null;
    }

    private static String pickWeighted(IrisProceduralTree tree, RNG rng) {
        int total = 0;
        for (art.arcane.iris.engine.object.IrisTreeSecondaryLeaf s : tree.getWeightedSecondaryLeaves()) {
            total += Math.max(0, s.getWeight());
        }
        if (total <= 0) {
            return null;
        }
        double r = rng.nextDouble() * total;
        double cumulative = 0;
        for (art.arcane.iris.engine.object.IrisTreeSecondaryLeaf s : tree.getWeightedSecondaryLeaves()) {
            cumulative += Math.max(0, s.getWeight());
            if (r < cumulative) {
                return s.getBlock();
            }
        }
        return tree.getWeightedSecondaryLeaves().get(tree.getWeightedSecondaryLeaves().size() - 1).getBlock();
    }

    private static BlockData resolveBlock(String block, IrisMaterialPalette palette, IrisData data, TreeBlockCanvas.Vec pos, RNG paletteRng) {
        if (TreeTrunkBuilder.paletteSet(palette)) {
            PlatformBlockState state = palette.get(paletteRng, pos.x(), pos.y(), pos.z(), data);
            return state == null ? null : ((BlockData) state.nativeHandle()).clone();
        }
        if (block != null && !block.isEmpty()) {
            return cloneOrNull(BukkitBlockResolution.getOrNull(block, false));
        }
        return null;
    }

    private static BlockData woodCap(BlockData bd) {
        String name = bd.getMaterial().name();
        String woodName = null;
        if (name.endsWith("_LOG")) {
            woodName = name.substring(0, name.length() - 4) + "_WOOD";
        } else if (name.endsWith("_STEM")) {
            woodName = name.substring(0, name.length() - 5) + "_HYPHAE";
        }
        if (woodName == null) {
            return bd;
        }
        try {
            Material wood = Material.valueOf(woodName);
            BlockData wb = wood.createBlockData();
            if (wb instanceof Orientable wo && bd instanceof Orientable bo) {
                wo.setAxis(bo.getAxis());
            }
            return wb;
        } catch (IllegalArgumentException ignored) {
            return bd;
        }
    }

    private static BlockData cloneOrNull(BlockData bd) {
        return bd == null ? null : bd.clone();
    }

    private static long positionHash(TreeBlockCanvas.Vec pos) {
        return pos.x() * 73856093L ^ pos.y() * 19349663L ^ pos.z() * 83492791L;
    }
}
