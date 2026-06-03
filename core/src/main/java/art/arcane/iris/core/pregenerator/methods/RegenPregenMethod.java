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

package art.arcane.iris.core.pregenerator.methods;

import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.pregenerator.PregenListener;
import art.arcane.iris.core.pregenerator.PregeneratorMethod;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import org.bukkit.World;

public class RegenPregenMethod implements PregeneratorMethod {
    private final World world;
    private final AsyncPregenMethod delegate;

    public RegenPregenMethod(World world) {
        this.world = world;
        this.delegate = new AsyncPregenMethod(world, 0);
    }

    @Override
    public void init() {
        delegate.init();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void save() {
        delegate.save();
    }

    @Override
    public boolean supportsRegions(int x, int z, PregenListener listener) {
        return delegate.supportsRegions(x, z, listener);
    }

    @Override
    public String getMethod(int x, int z) {
        return "Regen";
    }

    @Override
    public boolean isAsyncChunkMode() {
        return delegate.isAsyncChunkMode();
    }

    @Override
    public void generateRegion(int x, int z, PregenListener listener) {
        delegate.generateRegion(x, z, listener);
    }

    @Override
    public void generateChunk(int x, int z, PregenListener listener) {
        purge(x, z);
        delegate.generateChunk(x, z, listener);
    }

    @Override
    public Mantle getMantle() {
        return delegate.getMantle();
    }

    private void purge(int x, int z) {
        if (!IrisToolbelt.isIrisWorld(world)) {
            return;
        }

        Engine engine = IrisToolbelt.access(world).getEngine();
        if (engine == null) {
            return;
        }

        int radius = Math.max(0, engine.getMantle().getRealRadius());
        Mantle mantle = engine.getMantle().getMantle();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                mantle.deleteChunk(x + dx, z + dz);
            }
        }

        INMS.get().purgeChunk(world, x, z);
    }
}
