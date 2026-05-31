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
import art.arcane.volmlib.util.director.DirectorParameterHandler;

public class StructureModeHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
        KList<String> options = new KList<>();
        options.add("overwrite");
        options.add("add-only");
        options.add("merge");
        return options;
    }

    @Override
    public String toString(String value) {
        return value;
    }

    @Override
    public String parse(String in, boolean force) {
        return in == null || in.isBlank() ? "overwrite" : in.trim();
    }

    @Override
    public boolean supports(Class<?> type) {
        return type.equals(String.class);
    }

    @Override
    public String getRandomDefault() {
        return "overwrite";
    }
}
