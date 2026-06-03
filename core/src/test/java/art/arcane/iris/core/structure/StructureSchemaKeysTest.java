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

package art.arcane.iris.core.structure;

import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StructureSchemaKeysTest {
    @Test
    public void vanillaKeysAreNormalizedToIrisLoadKeyForm() {
        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("minecraft:village_plains", "minecraft:stronghold", "minecraft:ancient_city"),
                Collections.emptyList(),
                Collections.emptyList());

        assertTrue(result.contains("minecraft_village_plains"));
        assertTrue(result.contains("minecraft_stronghold"));
        assertTrue(result.contains("minecraft_ancient_city"));
    }

    @Test
    public void strongholdIsAlwaysOfferedEvenWithoutAnImportedFile() {
        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("minecraft:stronghold", "minecraft:fortress", "minecraft:monument"),
                Collections.emptyList(),
                Collections.emptyList());

        assertTrue(result.contains("minecraft_stronghold"));
        assertTrue(result.contains("minecraft_fortress"));
        assertTrue(result.contains("minecraft_monument"));
    }

    @Test
    public void datapackStructuresAreNormalizedAndIncluded() {
        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("nova_structures:temple", "Nova_Structures:Big/Castle"),
                Collections.emptyList(),
                Collections.emptyList());

        assertTrue(result.contains("nova_structures_temple"));
        assertTrue(result.contains("nova_structures_big_castle"));
    }

    @Test
    public void jigsawComponentPiecesAreExcluded() {
        List<String> pieces = Arrays.asList(
                "village/taiga/houses/taiga_small_house_5",
                "bastion/units/rampart_plates/plate_0",
                "village/common/animals/cat_black",
                "trial_chambers/corridor/straight_1",
                "end_city/third_floor_1",
                "igloo/middle");

        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("minecraft:village_taiga", "minecraft:bastion_remnant"),
                pieces,
                pieces);

        for (String piece : pieces) {
            assertFalse("component piece must not be a valid structure key: " + piece, result.contains(piece));
        }
        assertTrue(result.contains("minecraft_village_taiga"));
        assertTrue(result.contains("minecraft_bastion_remnant"));
    }

    @Test
    public void importedCustomStructuresAreKept() {
        KList<String> result = StructureSchemaKeys.collect(
                Collections.emptyList(),
                Arrays.asList("mypack_castle", "custom/cool_tower"),
                Collections.emptyList());

        assertTrue(result.contains("mypack_castle"));
        assertTrue(result.contains("custom/cool_tower"));
    }

    @Test
    public void importedStructureThatCollidesWithAPieceIsDropped() {
        KList<String> result = StructureSchemaKeys.collect(
                Collections.emptyList(),
                Arrays.asList("village/taiga/houses/taiga_small_house_5", "minecraft_village_plains"),
                Collections.singletonList("village/taiga/houses/taiga_small_house_5"));

        assertFalse(result.contains("village/taiga/houses/taiga_small_house_5"));
        assertTrue(result.contains("minecraft_village_plains"));
    }

    @Test
    public void vanillaAndImportedEquivalentsDeduplicate() {
        KList<String> result = StructureSchemaKeys.collect(
                Collections.singletonList("minecraft:ancient_city"),
                Collections.singletonList("minecraft_ancient_city"),
                Collections.emptyList());

        int occurrences = 0;
        for (String key : result) {
            if (key.equals("minecraft_ancient_city")) {
                occurrences++;
            }
        }
        assertEquals(1, occurrences);
    }

    @Test
    public void resultIsSorted() {
        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("minecraft:zombie_village", "minecraft:ancient_city", "minecraft:monument"),
                Collections.emptyList(),
                Collections.emptyList());

        KList<String> sorted = new KList<>(result);
        Collections.sort(sorted);
        assertEquals(sorted, result);
    }

    @Test
    public void nullCollectionsAreHandled() {
        KList<String> result = StructureSchemaKeys.collect(null, null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void blankAndNullEntriesAreIgnored() {
        KList<String> result = StructureSchemaKeys.collect(
                Arrays.asList("minecraft:village_plains", "", null, "   "),
                Arrays.asList("custom_one", "", null),
                Arrays.asList("", null));

        assertTrue(result.contains("minecraft_village_plains"));
        assertTrue(result.contains("custom_one"));
        assertEquals(2, result.size());
    }
}
