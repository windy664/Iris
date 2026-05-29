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
import art.arcane.iris.engine.object.annotations.RegistryListVanillaStructure;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Controls native vanilla & datapack structure generation for this dimension. The default mode is ALL_ON (everything generates).")
@Data
public class IrisVanillaStructureControl {
    @Desc("Master toggle. ALL_ON generates every vanilla & datapack structure except those in 'disabled'. ALL_OFF (or CUSTOM) generates nothing except those in 'enabled'.")
    private VanillaStructureMode mode = VanillaStructureMode.ALL_ON;

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn OFF while mode is ALL_ON, e.g. 'minecraft:stronghold'. A namespace:path prefix also matches, so 'minecraft:village' disables every village.")
    private KList<String> disabled = new KList<>();

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn ON while mode is ALL_OFF (or CUSTOM), e.g. 'minecraft:village_plains'. A namespace:path prefix also matches.")
    private KList<String> enabled = new KList<>();

    public boolean active() {
        return mode == VanillaStructureMode.ALL_ON || !enabled.isEmpty();
    }

    public boolean shouldGenerate(String key) {
        if (mode == VanillaStructureMode.ALL_ON) {
            return !matches(disabled, key);
        }
        return matches(enabled, key);
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
