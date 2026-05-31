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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.StructureDistribution;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Finds where IRIS_PLACED structures generate. A structure key matches either the iris
 * structure's own load key or its {@code vanillaSource} (so a vanilla key like
 * {@code minecraft:ancient_city} resolves to the imported {@code minecraft_ancient_city}).
 *
 * A per-pack index of placed keys is cached so {@code /locate} and {@code /iris goto} skip
 * the grid scan entirely for keys that are not placed (keeping them as fast as vanilla).
 *
 * For RANDOM_SPREAD placements (the common, vanilla-style spaced grid) the locator jumps
 * straight to the single candidate chunk in each spacing-sized grid cell rather than testing
 * every chunk -- roughly a {@code spacing^2} reduction in placement checks, matching vanilla
 * locate performance. Non-grid distributions (DENSITY / CONCENTRIC_RINGS) fall back to the
 * exhaustive per-chunk ring scan.
 */
public final class IrisStructureLocator {
    private static final Map<IrisData, PlacementIndex> INDEX_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private IrisStructureLocator() {
    }

    /** Iris structure load keys that are referenced by any IRIS_PLACED placement (for autocomplete). */
    public static Set<String> placedKeys(Engine engine) {
        if (engine == null) {
            return Collections.emptySet();
        }
        return index(engine).loadKeys;
    }

    public static boolean isPlaced(Engine engine, String key) {
        if (engine == null || key == null || key.isEmpty()) {
            return false;
        }
        PlacementIndex idx = index(engine);
        return idx.loadKeys.contains(key) || idx.vanillaSources.contains(key);
    }

    public static boolean suppressesVanilla(Engine engine, String vanillaKey) {
        if (engine == null || vanillaKey == null || vanillaKey.isEmpty()) {
            return false;
        }
        for (String source : index(engine).vanillaSources) {
            if (source.equalsIgnoreCase(vanillaKey)) {
                return true;
            }
        }
        return false;
    }

    public static boolean startsInChunk(Engine engine, String key, int cx, int cz) {
        if (!isPlaced(engine, key)) {
            return false;
        }
        long seed = engine.getSeedManager().getMantle();
        RNG rng = new RNG(Cache.key(cx, cz) + seed);
        for (IrisStructurePlacement placement : placementsAt(engine, cx, cz)) {
            if (!matches(placement, key, engine.getData())) {
                continue;
            }
            if (StructurePlacementGrid.startsInChunk(placement, cx, cz, seed, rng)) {
                return true;
            }
        }
        return false;
    }

    public static int[] locate(Engine engine, String key, int fromBlockX, int fromBlockZ, int maxRadiusChunks) {
        if (!isPlaced(engine, key)) {
            return null;
        }
        int max = Math.max(1, Math.min(maxRadiusChunks, 2048));

        List<int[]> gridParams = collectRandomSpreadParams(engine, key);
        if (gridParams == null || gridParams.isEmpty()) {
            // A non-grid distribution (DENSITY / CONCENTRIC_RINGS) matches this key, or no grid
            // params could be resolved; fall back to the exhaustive per-chunk scan.
            return locateByChunkScan(engine, key, fromBlockX, fromBlockZ, max);
        }

        long seed = engine.getSeedManager().getMantle();
        int pcx = fromBlockX >> 4;
        int pcz = fromBlockZ >> 4;
        long maxDistSq = (long) max * (long) max;
        long bestDistSq = Long.MAX_VALUE;
        int[] best = null;

        for (int[] params : gridParams) {
            int spacing = Math.max(1, params[0]);
            int separation = params[1];
            int salt = params[2];
            int centerCellX = Math.floorDiv(pcx, spacing);
            int centerCellZ = Math.floorDiv(pcz, spacing);
            int cellRadius = (max / spacing) + 2;
            int firstHitRing = -1;

            for (int r = 0; r <= cellRadius; r++) {
                if (firstHitRing >= 0 && r > firstHitRing + 1) {
                    break;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int[] candidate = StructurePlacementGrid.randomSpreadCellChunk(
                                centerCellX + dx, centerCellZ + dz, spacing, separation, salt, seed);
                        int cx = candidate[0];
                        int cz = candidate[1];
                        long ddx = (long) cx - pcx;
                        long ddz = (long) cz - pcz;
                        long distSq = ddx * ddx + ddz * ddz;
                        if (distSq > maxDistSq || distSq >= bestDistSq) {
                            continue;
                        }
                        if (startsInChunk(engine, key, cx, cz)) {
                            bestDistSq = distSq;
                            best = new int[]{cx << 4, structureY(engine, key, cx, cz), cz << 4};
                            if (firstHitRing < 0) {
                                firstHitRing = r;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    private static int[] locateByChunkScan(Engine engine, String key, int fromBlockX, int fromBlockZ, int max) {
        int pcx = fromBlockX >> 4;
        int pcz = fromBlockZ >> 4;
        for (int r = 0; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (startsInChunk(engine, key, cx, cz)) {
                        return new int[]{cx << 4, structureY(engine, key, cx, cz), cz << 4};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Collects the distinct {spacing, separation, salt} tuples of every RANDOM_SPREAD placement that
     * matches the key across the dimension, its regions and biomes. Returns {@code null} if any
     * matching placement uses a non-grid distribution (DENSITY / CONCENTRIC_RINGS), signalling the
     * caller to fall back to the exhaustive per-chunk scan.
     */
    private static List<int[]> collectRandomSpreadParams(Engine engine, String key) {
        IrisData data = engine.getData();
        List<int[]> params = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();

        KList<IrisStructurePlacement> all = new KList<>();
        all.addAll(engine.getDimension().getStructures());
        for (IrisRegion region : engine.getDimension().getAllRegions(engine)) {
            all.addAll(region.getStructures());
        }
        for (IrisBiome biome : engine.getDimension().getAllBiomes(engine)) {
            all.addAll(biome.getStructures());
        }

        for (IrisStructurePlacement placement : all) {
            if (placement == null) {
                continue;
            }
            if (!matches(placement, key, data)) {
                continue;
            }
            if (placement.getDistribution() != StructureDistribution.RANDOM_SPREAD) {
                return null;
            }
            int spacing = Math.max(1, placement.getSpacing());
            int separation = placement.getSeparation();
            int salt = placement.getSalt();
            long packed = ((long) spacing << 42) ^ ((long) (separation & 0x1FFFFF) << 21) ^ (salt & 0x1FFFFFL);
            if (seen.add(packed)) {
                params.add(new int[]{spacing, separation, salt});
            }
        }

        return params;
    }

    private static KList<IrisStructurePlacement> placementsAt(Engine engine, int cx, int cz) {
        int bx = 8 + (cx << 4);
        int bz = 8 + (cz << 4);
        IrisBiome biome = engine.getComplex().getTrueBiomeStream().get(bx, bz);
        IrisRegion region = engine.getComplex().getRegionStream().get(bx, bz);
        KList<IrisStructurePlacement> placements = new KList<>();
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (region != null) {
            placements.addAll(region.getStructures());
        }
        placements.addAll(engine.getDimension().getStructures());
        return placements;
    }

    private static boolean matches(IrisStructurePlacement placement, String key, IrisData data) {
        for (String structureKey : placement.getStructures()) {
            if (structureKey == null) {
                continue;
            }
            if (structureKey.equalsIgnoreCase(key)) {
                return true;
            }
            IrisStructure structure = IrisData.loadAnyStructure(structureKey, data);
            if (structure != null) {
                String vanillaSource = structure.getVanillaSource();
                if (vanillaSource != null && !vanillaSource.isEmpty() && vanillaSource.equalsIgnoreCase(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int structureY(Engine engine, String key, int cx, int cz) {
        IrisData data = engine.getData();
        int bx = 8 + (cx << 4);
        int bz = 8 + (cz << 4);
        for (IrisStructurePlacement placement : placementsAt(engine, cx, cz)) {
            if (!matches(placement, key, data)) {
                continue;
            }
            if (placement.isUnderground()) {
                int lo = Math.max(engine.getMinHeight() + 1, Math.min(placement.getMinHeight(), placement.getMaxHeight()));
                int hi = Math.min(engine.getMinHeight() + engine.getHeight() - 1, Math.max(placement.getMinHeight(), placement.getMaxHeight()));
                return (lo + hi) / 2;
            }
        }
        return engine.getHeight(bx, bz) + engine.getMinHeight();
    }

    private static PlacementIndex index(Engine engine) {
        IrisData data = engine.getData();
        PlacementIndex cached = INDEX_CACHE.get(data);
        if (cached != null) {
            return cached;
        }
        PlacementIndex built = build(engine, data);
        INDEX_CACHE.put(data, built);
        return built;
    }

    private static PlacementIndex build(Engine engine, IrisData data) {
        Set<String> loadKeys = new LinkedHashSet<>();
        Set<String> vanillaSources = new LinkedHashSet<>();
        collect(engine.getDimension().getStructures(), data, loadKeys, vanillaSources);
        for (IrisRegion region : engine.getDimension().getAllRegions(engine)) {
            collect(region.getStructures(), data, loadKeys, vanillaSources);
        }
        for (IrisBiome biome : engine.getDimension().getAllBiomes(engine)) {
            collect(biome.getStructures(), data, loadKeys, vanillaSources);
        }
        return new PlacementIndex(loadKeys, vanillaSources);
    }

    private static void collect(KList<IrisStructurePlacement> placements, IrisData data, Set<String> loadKeys, Set<String> vanillaSources) {
        if (placements == null) {
            return;
        }
        for (IrisStructurePlacement placement : placements) {
            if (placement == null) {
                continue;
            }
            for (String structureKey : placement.getStructures()) {
                if (structureKey == null || structureKey.isEmpty()) {
                    continue;
                }
                loadKeys.add(structureKey);
                IrisStructure structure = IrisData.loadAnyStructure(structureKey, data);
                if (structure != null) {
                    String vanillaSource = structure.getVanillaSource();
                    if (vanillaSource != null && !vanillaSource.isEmpty()) {
                        vanillaSources.add(vanillaSource);
                    }
                }
            }
        }
    }

    private static final class PlacementIndex {
        private final Set<String> loadKeys;
        private final Set<String> vanillaSources;

        private PlacementIndex(Set<String> loadKeys, Set<String> vanillaSources) {
            this.loadKeys = loadKeys;
            this.vanillaSources = vanillaSources;
        }
    }
}
