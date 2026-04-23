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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.data.cache.AtomicCache;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Required;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.iris.util.common.data.B;
import art.arcane.volmlib.util.collection.KMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

@Snippet("deposit-variant")
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Remaps ore block ids to alternate block ids within a vertical band. Ores declared at dimension, region, and biome scope can be rewritten at placement time (for example, iron_ore -> deepslate_iron_ore inside a deep carving band, or yourmod:iron -> yourmod:moon_iron inside a lunar biome).")
@Data
public class IrisDepositVariant {
    private final transient AtomicCache<KMap<Material, BlockData>> resolved = new AtomicCache<>();

    @Required
    @MinNumber(-2048)
    @MaxNumber(8192)
    @Desc("Inclusive minimum world Y this variant applies at.")
    private int minHeight = 0;

    @Required
    @MinNumber(-2048)
    @MaxNumber(8192)
    @Desc("Inclusive maximum world Y this variant applies at.")
    private int maxHeight = 0;

    @Required
    @Desc("Source block id (for example `minecraft:iron_ore`) -> replacement block id (for example `minecraft:deepslate_iron_ore`). Any block id the data loader resolves is accepted, including external/mod blocks. Source match is by material only, so block properties on the source key are ignored.")
    private KMap<String, String> remap = new KMap<>();

    public BlockData remapOrNull(BlockData ore, IrisData rdata) {
        if (ore == null || remap == null || remap.isEmpty()) {
            return null;
        }

        KMap<Material, BlockData> map = resolved.aquire(() -> buildResolved(rdata));
        return map.get(ore.getMaterial());
    }

    private KMap<Material, BlockData> buildResolved(IrisData rdata) {
        KMap<Material, BlockData> out = new KMap<>();

        for (java.util.Map.Entry<String, String> entry : remap.entrySet()) {
            BlockData source = B.getOrNull(entry.getKey(), false);
            BlockData target = B.getOrNull(entry.getValue(), true);

            if (source == null || target == null) {
                continue;
            }

            out.put(source.getMaterial(), target);
        }

        return out;
    }
}
