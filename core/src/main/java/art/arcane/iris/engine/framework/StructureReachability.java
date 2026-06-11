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
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.object.IrisWorld;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.collection.KList;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Determines which vanilla/datapack structures can ever generate in a world, using the same biome
 * gate vanilla applies: a structure is reachable when its biome filter intersects the set of vanilla
 * biomes the pack actually produces (its {@code possibleBiomes}). Structures whose required biomes are
 * never produced cannot generate and must not be scanned for by {@code /locate} or {@code /iris find}
 * (an unbounded scan for an absent biome is what stalls the server).
 *
 * <p>The reachable set is fixed per pack/world, so it is cached per {@link IrisData}.
 */
public final class StructureReachability {
    private static final Map<IrisData, Set<String>> REACHABLE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private StructureReachability() {
    }

    public static Set<String> reachableKeys(Engine engine) {
        if (engine == null) {
            return Collections.emptySet();
        }
        IrisData data = engine.getData();
        if (data == null) {
            return Collections.emptySet();
        }
        Set<String> cached = REACHABLE_CACHE.get(data);
        if (cached != null) {
            return cached;
        }
        Set<String> built = build(engine);
        REACHABLE_CACHE.put(data, built);
        return built;
    }

    public static boolean isReachable(Engine engine, String structureKey) {
        if (structureKey == null || structureKey.isEmpty()) {
            return false;
        }
        return reachableKeys(engine).contains(structureKey.toLowerCase());
    }

    public static void invalidate(Engine engine) {
        if (engine == null) {
            return;
        }
        IrisData data = engine.getData();
        if (data != null) {
            REACHABLE_CACHE.remove(data);
        }
    }

    private static Set<String> build(Engine engine) {
        IrisWorld world = engine.getWorld();
        if (world == null || world.realWorld() == null) {
            return Collections.emptySet();
        }
        Set<String> reachable = new LinkedHashSet<>();
        for (String key : INMS.get().getReachableStructureKeys(world.realWorld())) {
            if (key != null && !key.isEmpty()) {
                reachable.add(key.toLowerCase());
            }
        }
        return reachable;
    }

    /**
     * The vanilla biome keys (e.g. {@code minecraft:taiga}) from a structure's filter that the pack
     * does not produce. An empty list means the structure is reachable. Used by the structure verify
     * diagnostic to explain why a structure cannot generate.
     */
    public static KList<String> missingBiomeKeys(Engine engine, String structureKey) {
        KList<String> missing = new KList<>();
        if (engine == null || structureKey == null || structureKey.isEmpty()) {
            return missing;
        }
        IrisWorld world = engine.getWorld();
        if (world == null || world.realWorld() == null) {
            return missing;
        }
        Set<String> possible = new LinkedHashSet<>();
        for (String key : INMS.get().getPossibleBiomeKeys(world.realWorld())) {
            if (key != null) {
                possible.add(key.toLowerCase());
            }
        }
        for (String biomeKey : IrisPlatforms.get().structureHooks().structureBiomeKeys(structureKey)) {
            if (biomeKey != null && !possible.contains(biomeKey.toLowerCase())) {
                missing.add(biomeKey);
            }
        }
        return missing;
    }
}
