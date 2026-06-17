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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ModdedTileData extends TileData {
    public static final String NBT_PROPERTY = "nbt";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.LENIENT).create();

    private final byte[] raw;
    private final KMap<String, Object> tileProperties;

    ModdedTileData(byte[] raw, KMap<String, Object> tileProperties) {
        super();
        this.raw = raw;
        this.tileProperties = tileProperties == null ? new KMap<>() : tileProperties;
    }

    public static ModdedTileData capture(String blockKey, String snbt) throws IOException {
        KMap<String, Object> properties = new KMap<>();
        properties.put(NBT_PROPERTY, snbt);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(blockKey);
            out.writeUTF(GSON.toJson(properties));
        }
        return new ModdedTileData(bytes.toByteArray(), properties);
    }

    public String snbt() {
        Object value = tileProperties.get(NBT_PROPERTY);
        return value == null ? null : value.toString();
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
