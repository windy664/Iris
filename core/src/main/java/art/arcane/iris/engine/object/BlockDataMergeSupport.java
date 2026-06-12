package art.arcane.iris.engine.object;

import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.platform.bukkit.BukkitBlockResolution;
import org.bukkit.block.data.BlockData;

import java.util.function.Function;

public final class BlockDataMergeSupport {
    private static final boolean BUKKIT_PRESENT = detectBukkit();
    private static volatile StateMerger FALLBACK_MERGER = null;

    private BlockDataMergeSupport() {
    }

    public interface StateMerger {
        PlatformBlockState merge(PlatformBlockState base, PlatformBlockState update);
    }

    public static void bindFallbackMerger(StateMerger merger) {
        FALLBACK_MERGER = merger;
    }

    private static boolean detectBukkit() {
        try {
            Class.forName("org.bukkit.Bukkit", false, BlockDataMergeSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static PlatformBlockState merge(PlatformBlockState base, PlatformBlockState update) {
        if (!BUKKIT_PRESENT) {
            StateMerger merger = FALLBACK_MERGER;
            return merger == null ? mergeByKey(base, update) : merger.merge(base, update);
        }
        BlockData merged = merge((BlockData) base.nativeHandle(), (BlockData) update.nativeHandle(), BukkitBlockResolution::get);
        return merged == null ? null : BukkitBlockState.of(merged);
    }

    private static PlatformBlockState mergeByKey(PlatformBlockState base, PlatformBlockState update) {
        String key = update.key();
        int bracket = key.indexOf('[');
        if (bracket < 0) {
            return base;
        }
        PlatformBlockState merged = base;
        String body = key.substring(bracket + 1, key.lastIndexOf(']'));
        for (String entry : body.split(",")) {
            int equals = entry.indexOf('=');
            if (equals < 0) {
                continue;
            }
            merged = merged.withProperty(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
        }
        return merged;
    }

    static BlockData merge(BlockData base, BlockData update, Function<String, BlockData> resolver) {
        try {
            return base.merge(update);
        } catch (IllegalArgumentException e) {
            BlockData normalizedBase = resolve(base, resolver);
            BlockData normalizedUpdate = resolve(update, resolver);

            if (normalizedBase != null && normalizedUpdate != null) {
                try {
                    return normalizedBase.merge(normalizedUpdate);
                } catch (IllegalArgumentException ignored) {
                    return normalizedUpdate;
                }
            }

            if (normalizedUpdate != null) {
                return normalizedUpdate;
            }

            return update;
        }
    }

    private static BlockData resolve(BlockData data, Function<String, BlockData> resolver) {
        if (data == null || resolver == null) {
            return null;
        }

        String serialized = data.getAsString(false);
        if (serialized == null || serialized.isBlank()) {
            return null;
        }

        return resolver.apply(serialized);
    }
}
