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

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListVanillaStructure;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Controls native vanilla & ingested datapack structure generation for this dimension (set as the dimension's 'importedStructures' field). Default mode is ALL_ON (everything generates). Blacklist a few: mode=ALL_ON + list them in 'disabled'. Whitelist a few: mode=ALL_OFF + list them in 'enabled'. Both lists autocomplete every live vanilla AND ingested datapack structure key. Key matching is exact OR prefix (an entry matches when the structure key equals it or starts with it), so 'minecraft:village' covers every village variant. Run '/iris structure list <dimension>' to dump every valid key. Only affects NEWLY generated chunks, and is separate from the Iris 'structures' placement system (imported structures are placed by biome/region/dimension 'structures' lists, not here).")
@Data
public class IrisImportedStructureControl {
    @Desc("Master toggle. ALL_ON generates every vanilla & datapack structure except those in 'disabled'. ALL_OFF (or CUSTOM) generates nothing except those in 'enabled'.")
    private VanillaStructureMode mode = VanillaStructureMode.ALL_ON;

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn OFF while mode is ALL_ON, e.g. 'minecraft:stronghold'. A namespace:path prefix also matches, so 'minecraft:village' disables every village variant and 'minecraft:ruined_portal' disables every ruined portal. Ignored when mode is ALL_OFF.")
    private KList<String> disabled = new KList<>();

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn ON while mode is ALL_OFF (or CUSTOM), e.g. 'minecraft:village_plains'. A namespace:path prefix also matches, so 'minecraft:village' enables every village variant. Ignored when mode is ALL_ON.")
    private KList<String> enabled = new KList<>();

    @MinNumber(-512)
    @MaxNumber(512)
    @Desc("Vertical block offset applied only to UNDERGROUND vanilla structures (the UNDERGROUND_STRUCTURES and STRONGHOLDS generation steps: strongholds, trial chambers, mineshafts, ancient cities, etc.). Surface structures (villages, outposts, etc.) are never shifted. Use a negative value to push deep structures lower when your dimension's sea/terrain level differs from vanilla's (e.g. -64 if you lowered the fluid height to 0). 0 = no shift.")
    private int undergroundYShift = 0;

    @Desc("When true (the default), ingested datapacks generate normally: a datapack that redefines a VANILLA structure key (e.g. an ancient-city datapack overriding 'minecraft:ancient_city') REPLACES the vanilla placement, structure definition, jigsaw pools and pieces exactly as the datapack intends, and the datapack's OWN new structures generate too. When false, datapack structures do NOT generate at all - Iris strips the vanilla-key overrides from the installed datapack copy so the original vanilla structures generate untouched, and holds every datapack-namespaced structure out of natural generation so it never appears over or beside the vanilla one. Datapacks stay installed and importable either way, so when false the only way to get a datapack structure is to run '/iris structure import' and place it manually from a biome/region/dimension 'structures' list. Resolved globally across every loaded pack: if ANY dimension sets this false, vanilla-key overrides are stripped for every installed datapack.")
    private boolean datapackOverrides = true;

    public boolean active() {
        return mode == VanillaStructureMode.ALL_ON || !enabled.isEmpty();
    }

    public boolean shouldGenerate(String key) {
        if (!datapackOverrides && isDatapackKey(key)) {
            return false;
        }
        if (mode == VanillaStructureMode.ALL_ON) {
            return !matches(disabled, key);
        }
        return matches(enabled, key);
    }

    private static boolean isDatapackKey(String key) {
        return key != null && key.contains(":") && !key.startsWith("minecraft:");
    }

    private boolean matches(KList<String> list, String key) {
        if (key == null) {
            return false;
        }
        for (String entry : list) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            if (key.equals(entry) || key.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }
}
