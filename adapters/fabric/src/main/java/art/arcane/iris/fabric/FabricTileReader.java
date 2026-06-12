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

package art.arcane.iris.fabric;

import art.arcane.iris.engine.object.TileData;
import art.arcane.volmlib.util.collection.KMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BannerPattern;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

public final class FabricTileReader implements TileData.TileReader {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.LENIENT).create();
    private static final int DYE_COLOR_COUNT = 16;

    private final Supplier<MinecraftServer> server;

    public FabricTileReader(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    private static final class ReplayInputStream extends InputStream {
        private final InputStream source;
        private byte[] buffer = new byte[256];
        private int size = 0;
        private int position = 0;
        private int marked = 0;

        private ReplayInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            if (position < size) {
                int value = buffer[position] & 0xFF;
                position++;
                return value;
            }
            int value = source.read();
            if (value < 0) {
                return value;
            }
            if (size == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            buffer[size] = (byte) value;
            size++;
            position++;
            return value;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public synchronized void mark(int readLimit) {
            marked = position;
        }

        @Override
        public synchronized void reset() {
            position = marked;
        }

        private void rewind() {
            position = 0;
        }

        private int position() {
            return position;
        }

        private byte[] consumed() {
            return Arrays.copyOf(buffer, position);
        }
    }

    @Override
    public TileData read(DataInputStream in) throws IOException {
        if (!in.markSupported()) {
            throw new IOException("Mark not supported");
        }
        in.mark(Integer.MAX_VALUE);
        ReplayInputStream replay = new ReplayInputStream(in);
        DataInputStream din = new DataInputStream(replay);
        try {
            return parse(din, replay);
        } finally {
            in.reset();
            in.skipNBytes(replay.position());
            in.mark(0);
        }
    }

    private TileData parse(DataInputStream din, ReplayInputStream replay) throws IOException {
        try {
            String materialKey = din.readUTF();
            boolean materialMatched = matchMaterial(materialKey);
            String json = din.readUTF();
            KMap<String, Object> properties = kmapFromJson(json);
            if (!materialMatched) {
                throw new NullPointerException("material is marked non-null but is null");
            }
            if (properties == null) {
                throw new NullPointerException("properties is marked non-null but is null");
            }
            return new FabricTileData(replay.consumed(), properties);
        } catch (Throwable e) {
            replay.rewind();
            return parseLegacy(din, replay);
        }
    }

    @SuppressWarnings("unchecked")
    private static KMap<String, Object> kmapFromJson(String json) {
        return GSON.fromJson(json, KMap.class);
    }

    private TileData parseLegacy(DataInputStream din, ReplayInputStream replay) throws IOException {
        int id = din.readShort();
        switch (id) {
            case 0 -> readSign(din);
            case 1 -> readSpawner(din, replay);
            case 2 -> readBanner(din, replay);
            case 3 -> readLootable(din);
            default -> throw new IOException("Unknown tile type: " + id);
        }
        return new FabricTileData(replay.consumed(), new KMap<>());
    }

    private static void readSign(DataInputStream din) throws IOException {
        din.readUTF();
        din.readUTF();
        din.readUTF();
        din.readUTF();
        byte dye = din.readByte();
        if (dye < 0 || dye >= DYE_COLOR_COUNT) {
            throw new ArrayIndexOutOfBoundsException("Index " + dye + " out of bounds for length " + DYE_COLOR_COUNT);
        }
    }

    private static void readSpawner(DataInputStream din, ReplayInputStream replay) throws IOException {
        boolean resolved = false;
        replay.mark(Integer.MAX_VALUE);

        try {
            String keyString = din.readUTF();
            Identifier key = Identifier.tryParse(keyString);
            resolved = key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            if (!resolved) {
                replay.reset();
            }
        } catch (Throwable ignored) {
            replay.reset();
        }

        if (!resolved) {
            din.readShort();
        }
    }

    private void readBanner(DataInputStream din, ReplayInputStream replay) throws IOException {
        din.readUnsignedByte();
        int listSize = din.readUnsignedByte();
        replay.mark(Integer.MAX_VALUE);

        boolean parsedKeyed = false;
        try {
            for (int i = 0; i < listSize; i++) {
                din.readUnsignedByte();
                Identifier patternKey = Identifier.tryParse(din.readUTF());
                if (patternKey == null || !bannerPatternExists(patternKey)) {
                    throw new IOException("Unknown banner pattern key");
                }
            }
            parsedKeyed = true;
        } catch (Throwable ignored) {
            replay.reset();
        }

        if (parsedKeyed) {
            return;
        }

        for (int i = 0; i < listSize; i++) {
            din.readUnsignedByte();
            din.readUnsignedByte();
        }
    }

    private boolean bannerPatternExists(Identifier key) {
        MinecraftServer instance = server.get();
        if (instance == null) {
            return false;
        }
        Registry<BannerPattern> registry = instance.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        return registry.containsKey(key);
    }

    private static void readLootable(DataInputStream din) throws IOException {
        din.readUTF();
        din.readUTF();
        din.readLong();
    }

    private static boolean matchMaterial(String name) {
        String filtered = name;
        if (filtered.startsWith("minecraft:")) {
            filtered = filtered.substring("minecraft:".length());
        }
        filtered = filtered.toUpperCase(Locale.ROOT);
        filtered = filtered.replaceAll("\\s+", "_").replaceAll("\\W", "");
        Identifier identifier = Identifier.tryParse("minecraft:" + filtered.toLowerCase(Locale.ROOT));
        if (identifier == null) {
            return false;
        }
        return BuiltInRegistries.ITEM.containsKey(identifier) || BuiltInRegistries.BLOCK.containsKey(identifier);
    }
}
