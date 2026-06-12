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
import art.arcane.iris.engine.platform.BukkitChunkGenerator;
import art.arcane.iris.engine.platform.studio.generators.BiomeBuffetGenerator;
import art.arcane.iris.engine.platform.studio.generators.ObjectStudioGenerator;

@Desc("Represents a studio mode")
public enum StudioMode {
    NORMAL,
    BIOME_BUFFET_1x1,
    BIOME_BUFFET_3x3,
    BIOME_BUFFET_5x5,
    BIOME_BUFFET_9x9,
    BIOME_BUFFET_18x18,
    BIOME_BUFFET_36x36,
    REGION_BUFFET,
    OBJECT_BUFFET;

    public void inject(BukkitChunkGenerator c) {
        switch (this) {
            case NORMAL, REGION_BUFFET -> c.setStudioGenerator(null);
            case BIOME_BUFFET_1x1 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 1));
            case BIOME_BUFFET_3x3 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 3));
            case BIOME_BUFFET_5x5 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 5));
            case BIOME_BUFFET_9x9 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 9));
            case BIOME_BUFFET_18x18 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 18));
            case BIOME_BUFFET_36x36 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine(), 36));
            case OBJECT_BUFFET -> c.setStudioGenerator(new ObjectStudioGenerator(c.getEngine()));
        }
    }
}
