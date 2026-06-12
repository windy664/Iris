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

package art.arcane.iris.core.service;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardProvider;
import art.arcane.volmlib.util.board.BoardSettings;
import art.arcane.volmlib.util.board.ScoreDirection;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.matter.MatterCavern;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

public class BoardSVC implements IrisService, BoardProvider {
    private final KMap<Player, PlayerBoard> boards = new KMap<>();
    private BoardSettings settings;
    private boolean boardEnabled;

    @Override
    public void onEnable() {
        boardEnabled = true;
        settings = BoardSettings.builder()
                .boardProvider(this)
                .scoreDirection(ScoreDirection.DOWN)
                .build();

        cleanupLeakedMainScoreboard();

        for (Player player : art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getServer().getOnlinePlayers()) {
            J.runEntity(player, () -> updatePlayer(player));
        }
    }

    private void cleanupLeakedMainScoreboard() {
        try {
            if (Bukkit.getScoreboardManager() == null) {
                return;
            }
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            if (main == null) {
                return;
            }

            Objective named = main.getObjective("board");
            if (named != null) {
                named.unregister();
            }

            Objective sidebar = main.getObjective(DisplaySlot.SIDEBAR);
            if (sidebar != null && "board".equals(sidebar.getName())) {
                sidebar.unregister();
            }

            Team team = main.getTeam("board");
            if (team != null) {
                team.unregister();
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    @Override
    public void onDisable() {
        boardEnabled = false;
        for (PlayerBoard board : new ArrayList<>(boards.values())) {
            board.cancel();
        }
        boards.clear();
        settings = null;
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent e) {
        J.runEntity(e.getPlayer(), () -> updatePlayer(e.getPlayer()));
    }

    @EventHandler
    public void on(PlayerJoinEvent e) {
        J.runEntity(e.getPlayer(), () -> updatePlayer(e.getPlayer()));
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        remove(e.getPlayer());
    }

    public void updatePlayer(Player p) {
        if (!boardEnabled || settings == null) {
            return;
        }

        if (!J.isOwnedByCurrentRegion(p)) {
            J.runEntity(p, () -> updatePlayer(p));
            return;
        }

        if (isEligibleWorld(p)) {
            boards.computeIfAbsent(p, PlayerBoard::new);
            return;
        }

        remove(p);
    }

    private void remove(Player player) {
        if (player == null) {
            return;
        }

        if (!J.isOwnedByCurrentRegion(player)) {
            J.runEntity(player, () -> remove(player));
            return;
        }

        var board = boards.remove(player);
        if (board != null) {
            board.cancel();
        }
    }

    @Override
    public String getTitle(Player player) {
        return C.GREEN + "Iris";
    }

    @Override
    public List<String> getLines(Player player) {
        PlayerBoard board = boards.get(player);
        if (board == null) {
            return List.of();
        }
        return board.lines;
    }

    private boolean isEligibleWorld(Player player) {
        if (player == null) {
            return false;
        }

        World world = player.getWorld();
        if (!IrisToolbelt.isIrisWorld(world)) {
            return false;
        }

        PlatformChunkGenerator access = IrisToolbelt.access(world);
        return access != null && access.getEngine() != null;
    }

    @Data
    public class PlayerBoard {
        private final Player player;
        private final Board board;
        private volatile List<String> lines;
        private volatile boolean cancelled;

        public PlayerBoard(Player player) {
            this.player = player;
            try {
                if (Bukkit.getScoreboardManager() != null
                        && player.getScoreboard().equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
                    player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                }
            } catch (Throwable ignored) {
            }
            this.board = new Board(player, settings);
            this.lines = new ArrayList<>();
            this.cancelled = false;
            schedule(0);
        }

        private void schedule(int delayTicks) {
            if (cancelled || !boardEnabled || !player.isOnline()) {
                return;
            }
            J.runEntity(player, this::tick, delayTicks);
        }

        private void tick() {
            if (!boardEnabled || !player.isOnline()) {
                return;
            }

            if (cancelled) {
                board.remove();
                return;
            }

            if (!isEligibleWorld(player)) {
                boards.remove(player);
                cancelled = true;
                board.remove();
                return;
            }

            update();
            board.update();
            schedule(20);
        }

        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (J.isOwnedByCurrentRegion(player) && player.isOnline()) {
                board.remove();
            } else {
                J.runEntity(player, board::remove);
            }
        }

        public void update() {
            final World world = player.getWorld();
            final Location loc = player.getLocation();

            final var access = IrisToolbelt.access(world);
            if (access == null) return;

            final var engine = access.getEngine();
            if (engine == null) return;

            int x = loc.getBlockX();
            int y = loc.getBlockY() - world.getMinHeight();
            int z = loc.getBlockZ();

            List<String> lines = new ArrayList<>(this.lines.size());
            lines.add("&7&m                   ");
            lines.add(C.GREEN + "Speed" + C.GRAY + ":  " + Form.f(engine.getGeneratedPerSecond(), 0) + "/s " + Form.duration(1000D / engine.getGeneratedPerSecond(), 0));
            lines.add(C.AQUA + "Cache" + C.GRAY + ": " + Form.f(IrisData.cacheSize()));
            lines.add(C.AQUA + "Mantle" + C.GRAY + ": " + engine.getMantle().getLoadedRegionCount());

            if (IrisSettings.get().getGeneral().debug) {
                lines.add(C.LIGHT_PURPLE + "Carving" + C.GRAY + ": " + (engine.getMantle().getMantle().get(x, y, z, MatterCavern.class) != null));
            }

            lines.add("&7&m                   ");
            lines.add(C.AQUA + "Region" + C.GRAY + ": " + engine.getRegion(x, z).getName());
            lines.add(C.AQUA + "Biome" + C.GRAY + ":  " + engine.getBiomeOrMantle(x, y, z).getName());
            lines.add(C.AQUA + "Height" + C.GRAY + ": " + Math.round(engine.getHeight(x, z)));
            lines.add(C.AQUA + "Slope" + C.GRAY + ":  " + Form.f(engine.getComplex().getSlopeStream().get(x, z), 2));
            lines.add(C.AQUA + "BUD/s" + C.GRAY + ": " + Form.f(engine.getBlockUpdatesPerSecond()));
            lines.add("&7&m                   ");
            this.lines = lines;
        }
    }
}
