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

package art.arcane.iris.core.commands;

import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.DirectorHelp;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;

import java.util.List;

@Director(name = "datapack", aliases = {"datapacks", "dp"}, description = "Download & manage external datapack imports (Modrinth)")
public class CommandDatapack implements DirectorExecutor {
    @Director(description = "Show help tree for this command group", aliases = {"?"})
    public void help() {
        DirectorHelp.print(sender(), getClass());
    }

    @Director(description = "Download/update every datapack listed in a pack dimension's 'datapackImports' and install it into the world so its structures register like vanilla", aliases = {"pull", "update", "sync"})
    public void ingest(
            @Param(description = "Restart the server when new datapacks are installed (required for new structures to register and generate)", defaultValue = "false")
            boolean restart
    ) {
        VolmitSender sender = sender();
        sender.sendMessage(C.GRAY + "Starting datapack ingest...");
        J.a(() -> DatapackIngestService.ingestAll(sender, restart));
    }

    @Director(description = "List configured datapack imports and their installed versions", aliases = {"ls", "status"})
    public void list() {
        VolmitSender sender = sender();
        KList<String> configured = DatapackIngestService.collectConfiguredImports();
        List<DatapackIngestService.Entry> installed = DatapackIngestService.installed();

        sender.sendMessage(C.GREEN + "Configured datapack imports: " + C.WHITE + configured.size());
        for (String url : configured) {
            sender.sendMessage(C.GRAY + "  - " + C.WHITE + url);
        }

        sender.sendMessage(C.GREEN + "Installed datapacks: " + C.WHITE + installed.size());
        for (DatapackIngestService.Entry entry : installed) {
            sender.sendMessage(C.GRAY + "  - " + C.WHITE + entry.id + C.GRAY + " " + (entry.versionNumber == null ? "?" : entry.versionNumber));
        }

        if (configured.isEmpty()) {
            sender.sendMessage(C.YELLOW + "Add Modrinth URLs to a dimension's 'datapackImports' list, then run /iris datapack ingest.");
        }
    }

    @Director(description = "Remove an installed datapack by id (also delete its URL from datapackImports to keep it gone)", aliases = {"rm", "delete"})
    public void remove(
            @Param(description = "The datapack id (folder name) shown by /iris datapack list")
            String id
    ) {
        DatapackIngestService.remove(sender(), id);
    }
}
