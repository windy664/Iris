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

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Master toggle for vanilla & datapack structure generation in a dimension.")
public enum VanillaStructureMode {
    @Desc("All vanilla & datapack structures generate normally. This is the default. Per-set overrides may still disable individual sets.")
    ALL_ON,

    @Desc("All vanilla & datapack structures are suppressed. Per-set overrides may still enable individual sets.")
    ALL_OFF,

    @Desc("Nothing generates unless explicitly enabled via a per-set override with enabled=true.")
    CUSTOM
}
