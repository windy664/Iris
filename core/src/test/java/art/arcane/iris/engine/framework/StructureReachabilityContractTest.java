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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class StructureReachabilityContractTest {
    @Test
    public void reachableKeysIsEmptyForNullEngine() {
        assertTrue(StructureReachability.reachableKeys(null).isEmpty());
    }

    @Test
    public void reachableKeysIsEmptyWhenEngineHasNoData() {
        Engine engine = mock(Engine.class);
        assertTrue(StructureReachability.reachableKeys(engine).isEmpty());
    }

    @Test
    public void isReachableIsFalseForNullEngine() {
        assertFalse(StructureReachability.isReachable(null, "minecraft:village_taiga"));
    }

    @Test
    public void isReachableIsFalseForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertFalse(StructureReachability.isReachable(engine, null));
        assertFalse(StructureReachability.isReachable(engine, ""));
    }

    @Test
    public void isReachableIsFalseWhenNothingIsReachable() {
        Engine engine = mock(Engine.class);
        assertFalse(StructureReachability.isReachable(engine, "minecraft:village_taiga"));
    }

    @Test
    public void missingBiomeKeysIsEmptyForNullEngine() {
        assertTrue(StructureReachability.missingBiomeKeys(null, "minecraft:village_taiga").isEmpty());
    }

    @Test
    public void missingBiomeKeysIsEmptyForNullKey() {
        Engine engine = mock(Engine.class);
        assertTrue(StructureReachability.missingBiomeKeys(engine, null).isEmpty());
    }

    @Test
    public void missingBiomeKeysIsEmptyWhenWorldUnavailable() {
        Engine engine = mock(Engine.class);
        assertTrue(StructureReachability.missingBiomeKeys(engine, "minecraft:village_taiga").isEmpty());
    }

    @Test
    public void invalidateIsNullSafe() {
        StructureReachability.invalidate(null);
        StructureReachability.invalidate(mock(Engine.class));
    }
}
