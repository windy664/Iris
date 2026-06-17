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

import art.arcane.iris.modded.ModdedForcedDatapack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

public final class FabricForcedDatapackSources {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private FabricForcedDatapackSources() {
    }

    public static void attach(PackRepository repository) {
        if (repository == null) {
            return;
        }
        try {
            Set<RepositorySource> merged = new LinkedHashSet<>(repository.sources);
            merged.add(ModdedForcedDatapack.repositorySource());
            repository.sources = merged;
        } catch (Throwable e) {
            LOGGER.error("Iris failed to attach the forced startup datapack source to the server data pack repository", e);
        }
    }
}
