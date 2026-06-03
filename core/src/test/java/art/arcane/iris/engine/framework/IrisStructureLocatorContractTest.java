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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class IrisStructureLocatorContractTest {
    @Test
    public void placedKeysIsEmptyForNullEngine() {
        assertTrue(IrisStructureLocator.placedKeys(null).isEmpty());
    }

    @Test
    public void isPlacedIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.isPlaced(null, "minecraft:ancient_city"));
    }

    @Test
    public void isPlacedIsFalseForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertFalse(IrisStructureLocator.isPlaced(engine, null));
        assertFalse(IrisStructureLocator.isPlaced(engine, ""));
    }

    @Test
    public void suppressesVanillaIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.suppressesVanilla(null, "minecraft:ancient_city"));
    }

    @Test
    public void suppressesVanillaIsFalseForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertFalse(IrisStructureLocator.suppressesVanilla(engine, null));
        assertFalse(IrisStructureLocator.suppressesVanilla(engine, ""));
    }

    @Test
    public void startsInChunkIsFalseForNullEngine() {
        assertFalse(IrisStructureLocator.startsInChunk(null, "minecraft:ancient_city", 0, 0));
    }

    @Test
    public void locateReturnsNullForNullEngine() {
        assertNull(IrisStructureLocator.locate(null, "minecraft:village_taiga", 0, 0, 100));
    }

    @Test
    public void locateReturnsNullForNullOrEmptyKey() {
        Engine engine = mock(Engine.class);
        assertNull(IrisStructureLocator.locate(engine, null, 0, 0, 100));
        assertNull(IrisStructureLocator.locate(engine, "", 0, 0, 100));
    }
}
