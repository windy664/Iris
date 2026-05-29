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

package art.arcane.iris.core.edit;

import art.arcane.iris.Iris;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.BlockPosition;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import lombok.Data;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Set;
import java.util.function.Supplier;

@SuppressWarnings("ALL")
@Data
public class DustRevealer {
    private final Engine engine;
    private final World world;
    private final BlockPosition block;
    private final String key;
    private final KList<BlockPosition> hits;

    public DustRevealer(Engine engine, World world, BlockPosition block, String key, KList<BlockPosition> hits) {
        this.engine = engine;
        this.world = world;
        this.block = block;
        this.key = key;
        this.hits = hits;

        Location blockLocation = block.toBlock(world).getLocation();
        Runnable revealTask = () -> {
            BlockSignal.of(world, block.getX(), block.getY(), block.getZ(), 10);
            if (M.r(0.25)) {
                world.playSound(block.toBlock(world).getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, RNG.r.f(0.2f, 2f));
            }
            J.a(() -> {
                while (BlockSignal.active.get() > 128) {
                    J.sleep(5);
                }

                try {
                    is(new BlockPosition(block.getX() + 1, block.getY(), block.getZ()));
                    is(new BlockPosition(block.getX() - 1, block.getY(), block.getZ()));
                    is(new BlockPosition(block.getX(), block.getY() + 1, block.getZ()));
                    is(new BlockPosition(block.getX(), block.getY() - 1, block.getZ()));
                    is(new BlockPosition(block.getX(), block.getY(), block.getZ() + 1));
                    is(new BlockPosition(block.getX(), block.getY(), block.getZ() - 1));
                    is(new BlockPosition(block.getX() + 1, block.getY(), block.getZ() + 1));
                    is(new BlockPosition(block.getX() + 1, block.getY(), block.getZ() - 1));
                    is(new BlockPosition(block.getX() - 1, block.getY(), block.getZ() + 1));
                    is(new BlockPosition(block.getX() - 1, block.getY(), block.getZ() - 1));
                    is(new BlockPosition(block.getX() + 1, block.getY() + 1, block.getZ()));
                    is(new BlockPosition(block.getX() + 1, block.getY() - 1, block.getZ()));
                    is(new BlockPosition(block.getX() - 1, block.getY() + 1, block.getZ()));
                    is(new BlockPosition(block.getX() - 1, block.getY() - 1, block.getZ()));
                    is(new BlockPosition(block.getX(), block.getY() + 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX(), block.getY() + 1, block.getZ() + 1));
                    is(new BlockPosition(block.getX(), block.getY() - 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX(), block.getY() - 1, block.getZ() + 1));
                    is(new BlockPosition(block.getX() - 1, block.getY() + 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX() - 1, block.getY() + 1, block.getZ() + 1));
                    is(new BlockPosition(block.getX() - 1, block.getY() - 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX() - 1, block.getY() - 1, block.getZ() + 1));
                    is(new BlockPosition(block.getX() + 1, block.getY() + 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX() + 1, block.getY() + 1, block.getZ() + 1));
                    is(new BlockPosition(block.getX() + 1, block.getY() - 1, block.getZ() - 1));
                    is(new BlockPosition(block.getX() + 1, block.getY() - 1, block.getZ() + 1));
                } catch (Throwable e) {
                    Iris.reportError(e);
                    e.printStackTrace();
                }
            });
        };
        int delay = RNG.r.i(2, 8);
        if (!J.runAt(blockLocation, revealTask, delay)) {
            if (!J.isFolia()) {
                J.s(revealTask, delay);
            }
        }
    }

    public static void spawn(Block block, VolmitSender sender) {
        World world = block.getWorld();
        PlatformChunkGenerator generator = IrisToolbelt.access(world);
        if (generator == null) {
            return;
        }
        Engine access = generator.getEngine();

        if (access != null) {
            describe(access, world, block, sender);

            String a = access.getObjectPlacementKey(block.getX(), block.getY() - block.getWorld().getMinHeight(), block.getZ());
            if (a != null) {
                world.playSound(block.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 1f, 0.1f);

                sender.sendMessage("Found object " + a);
                J.a(() -> {
                    new DustRevealer(access, world, new BlockPosition(block.getX(), block.getY(), block.getZ()), a, new KList<>());
                });
            }
        }
    }

    private static void describe(Engine engine, World world, Block block, VolmitSender sender) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        int minHeight = world.getMinHeight();
        int relativeY = y - minHeight;
        int surfaceRelative = engine.getHeight(x, z, true);
        int surfaceY = surfaceRelative + minHeight;
        int offset = y - surfaceY;

        String objectKey = safe(() -> engine.getObjectPlacementKey(x, relativeY, z));
        IrisBiome surfaceBiome = safe(() -> engine.getSurfaceBiome(x, z));
        IrisBiome biomeHere = safe(() -> engine.getBiome(x, relativeY, z));
        IrisBiome caveBiome = safe(() -> engine.getCaveOrMantleBiome(x, relativeY, z));
        IrisRegion region = safe(() -> engine.getRegion(x, z));

        KList<String> lines = new KList<>();
        lines.add("Iris Dust @ " + x + ", " + y + ", " + z);
        lines.add("Block: " + block.getType().name());
        if (offset > 0) {
            lines.add("Position: +" + offset + " ABOVE surface (surface Y=" + surfaceY + ")");
        } else if (offset < 0) {
            lines.add("Position: " + (-offset) + " below surface (surface Y=" + surfaceY + ")");
        } else {
            lines.add("Position: at surface (Y=" + surfaceY + ")");
        }

        String placedBy;
        if (offset > 0) {
            placedBy = objectKey != null ? "object/stilt '" + objectKey + "' (above surface)" : "decoration/object/stilt (above surface)";
        } else if (objectKey != null) {
            placedBy = "buried object '" + objectKey + "'";
        } else {
            placedBy = "terrain layer (depth " + Math.max(0, surfaceRelative - relativeY) + " below surface)";
        }
        lines.add("Placed by: " + placedBy);
        lines.add("Object @block: " + (objectKey == null ? "none" : objectKey));
        if (objectKey == null) {
            String columnObject = findColumnObject(engine, x, relativeY, z, minHeight);
            lines.add("Column object: " + (columnObject == null
                    ? "none within 64 (decorator or terrain, NOT an object stilt)"
                    : columnObject + " -> this block is likely that object's stilt"));
        }

        if (surfaceBiome != null) {
            lines.add("Surface biome: " + surfaceBiome.getLoadKey() + " (" + biomeKey(surfaceBiome.getDerivative()) + ")");
        }
        if (biomeHere != null && (surfaceBiome == null || !biomeHere.getLoadKey().equals(surfaceBiome.getLoadKey()))) {
            lines.add("Biome @Y: " + biomeHere.getLoadKey());
        }
        if (caveBiome != null && (surfaceBiome == null || !caveBiome.getLoadKey().equals(surfaceBiome.getLoadKey()))) {
            lines.add("Cave/Mantle biome: " + caveBiome.getLoadKey());
        }

        try {
            lines.add("Server biome: " + INMS.get().getTrueBiomeBaseKey(block.getLocation())
                    + " (ID: " + INMS.get().getTrueBiomeBaseId(INMS.get().getTrueBiomeBase(block.getLocation())) + ")");
        } catch (Throwable e) {
            Iris.reportError(e);
        }

        if (region != null) {
            lines.add("Region: " + region.getLoadKey() + " (" + region.getName() + ")");
        }

        Set<String> objects = safe(() -> engine.getObjectsAt(x >> 4, z >> 4));
        if (objects != null && !objects.isEmpty()) {
            lines.add("Objects in chunk: " + objects);
        }

        sender.sendMessage(C.IRIS + "--- " + lines.get(0) + " ---");
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String color = (line.startsWith("Position:") || line.startsWith("Placed by:")) ? C.YELLOW.toString() : C.WHITE.toString();
            sender.sendMessage(color + line);
        }
        sendCopyButton(sender, String.join("\n", lines));
    }

    private static String findColumnObject(Engine engine, int x, int relativeY, int z, int minHeight) {
        for (int dy = 1; dy <= 64; dy++) {
            if (relativeY + dy >= 0) {
                try {
                    String up = engine.getObjectPlacementKey(x, relativeY + dy, z);
                    if (up != null) {
                        return up + " @Y=" + (relativeY + dy + minHeight) + " (above)";
                    }
                } catch (Throwable ignored) {
                }
            }
            if (relativeY - dy >= 0) {
                try {
                    String down = engine.getObjectPlacementKey(x, relativeY - dy, z);
                    if (down != null) {
                        return down + " @Y=" + (relativeY - dy + minHeight) + " (below)";
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String biomeKey(Biome biome) {
        if (biome == null) {
            return "none";
        }
        try {
            return biome.getKey().getKey();
        } catch (Throwable ignored) {
            return biome.toString();
        }
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            Iris.reportError(e);
            return null;
        }
    }

    private static void sendCopyButton(VolmitSender sender, String payload) {
        if (!sender.isPlayer()) {
            return;
        }
        try {
            Component button = Component.text("[Click to copy these stats]")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.copyToClipboard(payload))
                    .hoverEvent(HoverEvent.showText(Component.text("Copy block stats to clipboard")));
            Iris.audiences.player(sender.player()).sendMessage(button);
        } catch (Throwable e) {
            Iris.reportError(e);
        }
    }

    private boolean is(BlockPosition a) {
        if (a.getY() < world.getMinHeight() || a.getY() >= world.getMaxHeight()) {
            return false;
        }
        int betterY = a.getY() - world.getMinHeight();
        if (isValidTry(a) && engine.getObjectPlacementKey(a.getX(), betterY, a.getZ()) != null && engine.getObjectPlacementKey(a.getX(), betterY, a.getZ()).equals(key)) {
            hits.add(a);
            new DustRevealer(engine, world, a, key, hits);
            return true;
        }

        return false;
    }

    private boolean isValidTry(BlockPosition b) {
        return !hits.contains(b);
    }
}
