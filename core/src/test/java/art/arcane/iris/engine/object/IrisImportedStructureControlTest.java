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

package art.arcane.iris.engine.object;

import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisImportedStructureControlTest {
    private static KList<String> keys(String... values) {
        KList<String> list = new KList<>();
        for (String value : values) {
            list.add(value);
        }
        return list;
    }

    @Test
    public void defaultModeGeneratesEveryVanillaStructure() {
        IrisImportedStructureControl control = new IrisImportedStructureControl();
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
        assertTrue(control.shouldGenerate("minecraft:stronghold"));
        assertTrue(control.shouldGenerate("minecraft:ocean_monument"));
    }

    @Test
    public void defaultModeGeneratesDatapackStructures() {
        IrisImportedStructureControl control = new IrisImportedStructureControl();
        assertTrue(control.isDatapackOverrides());
        assertTrue(control.shouldGenerate("nova_structures:desert_temple"));
    }

    @Test
    public void disabledBlacklistMatchesExactKey() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDisabled(keys("minecraft:stronghold"));
        assertFalse(control.shouldGenerate("minecraft:stronghold"));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
    }

    @Test
    public void disabledBlacklistMatchesNamespacePrefix() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDisabled(keys("minecraft:village"));
        assertFalse(control.shouldGenerate("minecraft:village_plains"));
        assertFalse(control.shouldGenerate("minecraft:village_taiga"));
        assertFalse(control.shouldGenerate("minecraft:village_snowy"));
        assertTrue(control.shouldGenerate("minecraft:stronghold"));
    }

    @Test
    public void allOffWhitelistMatchesExactKey() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF)
                .setEnabled(keys("minecraft:village_plains"));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
        assertFalse(control.shouldGenerate("minecraft:village_taiga"));
        assertFalse(control.shouldGenerate("minecraft:stronghold"));
    }

    @Test
    public void allOffWhitelistMatchesNamespacePrefix() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF)
                .setEnabled(keys("minecraft:village"));
        assertTrue(control.shouldGenerate("minecraft:village_taiga"));
        assertTrue(control.shouldGenerate("minecraft:village_desert"));
        assertFalse(control.shouldGenerate("minecraft:stronghold"));
    }

    @Test
    public void customModeBehavesLikeWhitelist() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.CUSTOM)
                .setEnabled(keys("minecraft:village_plains"));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
        assertFalse(control.shouldGenerate("minecraft:stronghold"));
    }

    @Test
    public void datapackOverridesFalseBlocksDatapackKeysWhileAllOn() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDatapackOverrides(false);
        assertFalse(control.shouldGenerate("nova_structures:desert_temple"));
        assertFalse(control.shouldGenerate("aquaculture:treasure_vault"));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
    }

    @Test
    public void datapackOverridesFalseBlocksDatapackKeyEvenWhenWhitelisted() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF)
                .setEnabled(keys("nova_structures:desert_temple", "minecraft:village_plains"))
                .setDatapackOverrides(false);
        assertFalse(control.shouldGenerate("nova_structures:desert_temple"));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
    }

    @Test
    public void datapackOverridesTrueAllowsDatapackKeysWhileAllOn() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDatapackOverrides(true);
        assertTrue(control.shouldGenerate("nova_structures:desert_temple"));
    }

    @Test
    public void keyWithoutNamespaceIsNotTreatedAsDatapack() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDatapackOverrides(false);
        assertTrue(control.shouldGenerate("village_plains"));
    }

    @Test
    public void minecraftKeyIsNeverTreatedAsDatapack() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDatapackOverrides(false);
        assertTrue(control.shouldGenerate("minecraft:ancient_city"));
    }

    @Test
    public void activeTrueWhenAllOn() {
        assertTrue(new IrisImportedStructureControl().active());
    }

    @Test
    public void activeFalseWhenAllOffWithoutWhitelist() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF);
        assertFalse(control.active());
    }

    @Test
    public void activeTrueWhenAllOffWithWhitelist() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF)
                .setEnabled(keys("minecraft:village_plains"));
        assertTrue(control.active());
    }

    @Test
    public void activeFalseWhenCustomWithoutWhitelist() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.CUSTOM);
        assertFalse(control.active());
    }

    @Test
    public void emptyOrNullListEntriesNeverMatchEveryKey() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setDisabled(keys("", null));
        assertTrue(control.shouldGenerate("minecraft:village_plains"));
        assertTrue(control.shouldGenerate("minecraft:stronghold"));
    }

    @Test
    public void nullKeyIsNullSafeAndNotWhitelisted() {
        IrisImportedStructureControl control = new IrisImportedStructureControl()
                .setMode(VanillaStructureMode.ALL_OFF)
                .setEnabled(keys("minecraft:village_plains"));
        assertFalse(control.shouldGenerate(null));
    }
}
