package art.arcane.iris.util.common.data;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.data.BSupport;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.engine.object.IrisCompat;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.link.Identifier;
import art.arcane.iris.core.link.data.DataType;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.container.BlockProperty;
import art.arcane.iris.core.service.ExternalDataSVC;
import art.arcane.iris.util.common.data.registry.Materials;
import art.arcane.iris.util.common.reflect.KeyedType;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class B {
    private static final BSupportImpl BASE = new BSupportImpl();

    private static final class BSupportImpl extends BSupport<BlockProperty> {
        @Override
        protected void warn(String message) {
            IrisLogging.warn(message);
        }

        @Override
        protected void debug(String message) {
            IrisLogging.debug(message);
        }

        @Override
        protected void reportError(Throwable throwable) {
            IrisLogging.reportError(throwable);
        }

        @Override
        protected void error(String message) {
            IrisLogging.error(message);
        }

        @Override
        protected Material shortGrassMaterial() {
            return Materials.GRASS;
        }

        @Override
        protected void appendExtraFoliageMaterials(IntSet foliage) {
            // Iris has no extra foliage materials beyond Bukkit Material.
        }

        @Override
        protected boolean includeLitInUpdatable() {
            return false;
        }

        @Override
        protected BlockData resolveCompatBlock(String bdxf) {
            if (bdxf.contains(":")) {
                if (bdxf.startsWith("minecraft:")) {
                    return IrisServices.get(IrisCompat.class).getBlock(bdxf);
                }
                return null;
            }
            return IrisServices.get(IrisCompat.class).getBlock(bdxf);
        }

        @Override
        protected BlockData resolveExternalBlockData(String ix) {
            if (ix.startsWith("minecraft:") || !ix.contains(":")) {
                return null;
            }

            Identifier key = Identifier.fromString(ix);
            Optional<BlockData> bd = IrisServices.get(ExternalDataSVC.class).getBlockData(key);
            debug("Loading block data " + key);
            return bd.orElse(null);
        }

        @Override
        protected boolean shouldPreventLeafDecay() {
            return IrisSettings.get().getGenerator().isPreventLeafDecay();
        }

        @Override
        protected void appendExternalBlockTypes(KList<String> blockTypes) {
            for (Identifier id : IrisServices.get(ExternalDataSVC.class).getAllIdentifiers(DataType.BLOCK)) {
                blockTypes.add(id.toString());
            }
        }

        @Override
        protected void appendExternalItemTypes(KList<String> itemTypes) {
            for (Identifier id : IrisServices.get(ExternalDataSVC.class).getAllIdentifiers(DataType.ITEM)) {
                itemTypes.add(id.toString());
            }
        }

        @Override
        protected KMap<List<String>, List<BlockProperty>> loadExternalBlockStates() {
            KMap<List<BlockProperty>, List<String>> flipped = new KMap<>();
            INMS.get().getBlockProperties().forEach((k, v) -> {
                NamespacedKey key = KeyedType.getKey(k);
                String serialized = key == null ? k.name().toLowerCase(Locale.ROOT) : key.toString();
                flipped.computeIfAbsent(v, $ -> new KList<>()).add(serialized);
            });

            var emptyStates = flipped.computeIfAbsent(new KList<>(0), $ -> new KList<>());
            for (var pair : IrisServices.get(ExternalDataSVC.class).getAllBlockProperties()) {
                if (pair.getB().isEmpty()) {
                    emptyStates.add(pair.getA().toString());
                } else {
                    flipped.computeIfAbsent(pair.getB(), $ -> new KList<>()).add(pair.getA().toString());
                }
            }

            KMap<List<String>, List<BlockProperty>> states = new KMap<>();
            flipped.forEach((k, v) -> {
                var old = states.put(v, k);
                if (old != null) {
                    error("Duplicate block state: " + v + " (" + old + " and " + k + ")");
                }
            });

            return states;
        }
    }

    public static BlockData toDeepSlateOre(BlockData block, BlockData ore) {
        return BASE.toDeepSlateOre(block, ore);
    }

    public static art.arcane.iris.spi.PlatformBlockState toDeepSlateOre(art.arcane.iris.spi.PlatformBlockState block, art.arcane.iris.spi.PlatformBlockState ore) {
        return art.arcane.iris.platform.bukkit.BukkitBlockState.of(BASE.toDeepSlateOre((BlockData) block.nativeHandle(), (BlockData) ore.nativeHandle()));
    }

    public static boolean isOre(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isOre(state == null ? null : (BlockData) state.nativeHandle());
    }

    public static boolean isDeepSlate(BlockData blockData) {
        return BASE.isDeepSlate(blockData);
    }

    public static boolean isOre(BlockData blockData) {
        return BASE.isOre(blockData);
    }

    public static boolean canPlaceOnto(Material mat, Material onto) {
        return BASE.canPlaceOnto(mat, onto);
    }

    public static boolean canPlaceOnto(art.arcane.iris.spi.PlatformBlockState mat, art.arcane.iris.spi.PlatformBlockState onto) {
        return BASE.canPlaceOnto(((BlockData) mat.nativeHandle()).getMaterial(), ((BlockData) onto.nativeHandle()).getMaterial());
    }

    public static boolean isFoliagePlantable(BlockData d) {
        return BASE.isFoliagePlantable(d);
    }

    public static boolean isFoliagePlantable(Material d) {
        return BASE.isFoliagePlantable(d);
    }

    public static boolean isWater(BlockData b) {
        return BASE.isWater(b);
    }

    public static boolean isWater(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isWater(state == null ? null : (BlockData) state.nativeHandle());
    }

    public static BlockData getAir() {
        return BASE.getAir();
    }

    public static Material getMaterialOrNull(String bdx) {
        return BASE.getMaterialOrNull(bdx);
    }

    public static Material getMaterial(String bdx) {
        return BASE.getMaterial(bdx);
    }

    public static boolean isSolid(BlockData mat) {
        return BASE.isSolid(mat);
    }

    public static boolean isSolid(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isSolid(state == null ? null : (BlockData) state.nativeHandle());
    }

    public static BlockData getOrNull(String bdxf) {
        return BASE.getOrNull(bdxf);
    }

    public static BlockData getOrNull(String bdxf, boolean warn) {
        return BASE.getOrNull(bdxf, warn);
    }

    public static BlockData getNoCompat(String bdxf) {
        return BASE.getNoCompat(bdxf);
    }

    public static BlockData get(String bdxf) {
        return BASE.get(bdxf);
    }

    public static art.arcane.iris.spi.PlatformBlockState getState(String bdxf) {
        return art.arcane.iris.platform.bukkit.BukkitBlockState.of(BASE.get(bdxf));
    }

    public static art.arcane.iris.spi.PlatformBlockState getStateOrNull(String bdxf) {
        BlockData data = BASE.getOrNull(bdxf);
        return data == null ? null : art.arcane.iris.platform.bukkit.BukkitBlockState.of(data);
    }

    public static boolean isStorage(BlockData mat) {
        return BASE.isStorage(mat);
    }

    public static boolean isStorageChest(BlockData mat) {
        return BASE.isStorageChest(mat);
    }

    public static boolean isLit(BlockData mat) {
        return BASE.isLit(mat);
    }

    public static boolean isUpdatable(BlockData mat) {
        return BASE.isUpdatable(mat);
    }

    public static boolean isFoliage(Material d) {
        return BASE.isFoliage(d);
    }

    public static boolean isFoliage(BlockData d) {
        return BASE.isFoliage(d);
    }

    public static boolean isDecorant(BlockData m) {
        return BASE.isDecorant(m);
    }

    public static boolean isDecorant(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isDecorant(state == null ? null : (BlockData) state.nativeHandle());
    }

    public static KList<BlockData> get(KList<String> find) {
        return BASE.get(find);
    }

    public static boolean isFluid(BlockData d) {
        return BASE.isFluid(d);
    }

    public static boolean isFluid(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isFluid(state == null ? null : (BlockData) state.nativeHandle());
    }

    public static boolean matches(art.arcane.iris.spi.PlatformBlockState filter, art.arcane.iris.spi.PlatformBlockState state) {
        return ((BlockData) filter.nativeHandle()).matches((BlockData) state.nativeHandle());
    }

    public static boolean isAirOrFluid(BlockData d) {
        return BASE.isAirOrFluid(d);
    }

    public static boolean isAirOrFluid(art.arcane.iris.spi.PlatformBlockState state) {
        return BASE.isAirOrFluid((BlockData) state.nativeHandle());
    }

    public static boolean isAir(BlockData d) {
        return BASE.isAir(d);
    }

    public synchronized static String[] getBlockTypes() {
        return BASE.getBlockTypes();
    }

    public synchronized static KMap<List<String>, List<BlockProperty>> getBlockStates() {
        return BASE.getBlockStates();
    }

    public static String[] getItemTypes() {
        return BASE.getItemTypes();
    }

    public static boolean isWaterLogged(BlockData b) {
        return BASE.isWaterLogged(b);
    }

    public static void registerCustomBlockData(String namespace, String key, BlockData blockData) {
        BASE.registerCustomBlockData(namespace, key, blockData);
    }

    public static boolean isVineBlock(BlockData data) {
        return BASE.isVineBlock(data);
    }
}
