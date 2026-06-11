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

package art.arcane.iris.engine.modifier;

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.EngineAssignedModifier;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.common.data.B;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.spi.PlatformBlockState;
import org.bukkit.Material;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class IrisPerfectionModifier extends EngineAssignedModifier<PlatformBlockState> {
    private static final PlatformBlockState AIR = B.getState("AIR");
    private static final PlatformBlockState WATER = B.getState("WATER");
    private static final Map<Material, PlatformBlockState> ORE_BASES = buildOreBases();

    public IrisPerfectionModifier(Engine engine) {
        super(engine, "Perfection");
    }

    private static Map<Material, PlatformBlockState> buildOreBases() {
        Map<Material, PlatformBlockState> map = new EnumMap<>(Material.class);
        PlatformBlockState stone = B.getState("STONE");
        PlatformBlockState deepslate = B.getState("DEEPSLATE");
        PlatformBlockState netherrack = B.getState("NETHERRACK");
        PlatformBlockState blackstone = B.getState("BLACKSTONE");
        map.put(Material.COAL_ORE, stone);
        map.put(Material.COPPER_ORE, stone);
        map.put(Material.IRON_ORE, stone);
        map.put(Material.GOLD_ORE, stone);
        map.put(Material.REDSTONE_ORE, stone);
        map.put(Material.LAPIS_ORE, stone);
        map.put(Material.DIAMOND_ORE, stone);
        map.put(Material.EMERALD_ORE, stone);
        map.put(Material.DEEPSLATE_COAL_ORE, deepslate);
        map.put(Material.DEEPSLATE_COPPER_ORE, deepslate);
        map.put(Material.DEEPSLATE_IRON_ORE, deepslate);
        map.put(Material.DEEPSLATE_GOLD_ORE, deepslate);
        map.put(Material.DEEPSLATE_REDSTONE_ORE, deepslate);
        map.put(Material.DEEPSLATE_LAPIS_ORE, deepslate);
        map.put(Material.DEEPSLATE_DIAMOND_ORE, deepslate);
        map.put(Material.DEEPSLATE_EMERALD_ORE, deepslate);
        map.put(Material.NETHER_GOLD_ORE, netherrack);
        map.put(Material.NETHER_QUARTZ_ORE, netherrack);
        map.put(Material.ANCIENT_DEBRIS, netherrack);
        map.put(Material.GILDED_BLACKSTONE, blackstone);
        return map;
    }

    @Override
    public void onModify(int x, int z, Hunk<PlatformBlockState> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        if (getDimension().isHideOresForHiddenOre()) {
            hideOres(output, multicore);
        }
        AtomicBoolean changed = new AtomicBoolean(true);
        int passes = 0;
        AtomicInteger changes = new AtomicInteger();
        List<Integer> surfaces = new ArrayList<>();
        List<Integer> ceilings = new ArrayList<>();
        BurstExecutor burst = burst().burst(multicore);
        while (changed.get()) {
            passes++;
            changed.set(false);
            for (int i = 0; i < 16; i++) {
                int finalI = i;
                burst.queue(() -> {
                    for (int j = 0; j < 16; j++) {
                        surfaces.clear();
                        ceilings.clear();
                        int top = getHeight(output, finalI, j);
                        boolean inside = true;
                        surfaces.add(top);

                        for (int k = top; k >= 0; k--) {
                            PlatformBlockState b = output.get(finalI, k, j);
                            BlockData rawB = unwrap(b);
                            boolean now = b != null && !(BukkitBlockResolution.isAir(rawB) || BukkitBlockResolution.isFluid(rawB));

                            if (now != inside) {
                                inside = now;

                                if (inside) {
                                    surfaces.add(k);
                                } else {
                                    ceilings.add(k + 1);
                                }
                            }
                        }

                        for (int k : surfaces) {
                            PlatformBlockState tip = output.get(finalI, k, j);

                            if (tip == null) {
                                continue;
                            }

                            boolean remove = false;
                            boolean remove2 = false;

                            BlockData rawTip = (BlockData) tip.nativeHandle();
                            if (BukkitBlockResolution.isDecorant(rawTip)) {
                                BlockData bel = unwrap(output.get(finalI, k - 1, j));

                                if (bel == null) {
                                    remove = true;
                                } else if (!BukkitBlockResolution.canPlaceOnto(rawTip.getMaterial(), bel.getMaterial())) {
                                    remove = true;
                                } else if (bel instanceof Bisected) {
                                    BlockData bb = unwrap(output.get(finalI, k - 2, j));
                                    if (bb == null || !BukkitBlockResolution.canPlaceOnto(bel.getMaterial(), bb.getMaterial())) {
                                        remove = true;
                                        remove2 = true;
                                    }
                                }

                                if (remove) {
                                    changed.set(true);
                                    changes.getAndIncrement();
                                    output.set(finalI, k, j, AIR);

                                    if (remove2) {
                                        changes.getAndIncrement();
                                        output.set(finalI, k - 1, j, AIR);
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }

        getEngine().getMetrics().getPerfection().put(p.getMilliseconds());
    }

    private void hideOres(Hunk<PlatformBlockState> output, boolean multicore) {
        BurstExecutor burst = burst().burst(multicore);
        int height = output.getHeight();
        for (int i = 0; i < 16; i++) {
            int finalI = i;
            burst.queue(() -> {
                for (int j = 0; j < 16; j++) {
                    for (int k = height - 1; k >= 0; k--) {
                        PlatformBlockState block = output.get(finalI, k, j);
                        if (block == null) {
                            continue;
                        }
                        PlatformBlockState base = ORE_BASES.get(((BlockData) block.nativeHandle()).getMaterial());
                        if (base != null) {
                            output.set(finalI, k, j, base);
                        }
                    }
                }
            });
        }
        burst.complete();
    }

    private int getHeight(Hunk<PlatformBlockState> output, int x, int z) {
        for (int i = output.getHeight() - 1; i >= 0; i--) {
            PlatformBlockState b = output.get(x, i, z);

            if (b != null) {
                BlockData rawB = (BlockData) b.nativeHandle();
                if (!BukkitBlockResolution.isAir(rawB) && !BukkitBlockResolution.isFluid(rawB)) {
                    return i;
                }
            }
        }

        return 0;
    }

    private static BlockData unwrap(PlatformBlockState state) {
        return state == null ? null : (BlockData) state.nativeHandle();
    }
}
