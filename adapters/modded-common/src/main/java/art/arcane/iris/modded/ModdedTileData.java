/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.modded;

import art.arcane.iris.engine.object.TileData;
import art.arcane.volmlib.util.collection.KMap;

import java.io.DataOutputStream;
import java.io.IOException;

public final class ModdedTileData extends TileData {
    private final byte[] raw;
    private final KMap<String, Object> tileProperties;

    ModdedTileData(byte[] raw, KMap<String, Object> tileProperties) {
        super();
        this.raw = raw;
        this.tileProperties = tileProperties == null ? new KMap<>() : tileProperties;
    }

    @Override
    public KMap<String, Object> getProperties() {
        return tileProperties;
    }

    @Override
    public void toBinary(DataOutputStream out) throws IOException {
        out.write(raw);
    }

    @Override
    public TileData clone() {
        return this;
    }
}
