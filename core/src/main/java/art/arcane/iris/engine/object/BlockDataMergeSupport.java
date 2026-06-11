package art.arcane.iris.engine.object;

import art.arcane.iris.platform.bukkit.BukkitBlockState;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.platform.bukkit.BukkitBlockResolution;
import org.bukkit.block.data.BlockData;

import java.util.function.Function;

final class BlockDataMergeSupport {
    private BlockDataMergeSupport() {
    }

    static PlatformBlockState merge(PlatformBlockState base, PlatformBlockState update) {
        BlockData merged = merge((BlockData) base.nativeHandle(), (BlockData) update.nativeHandle(), BukkitBlockResolution::get);
        return merged == null ? null : BukkitBlockState.of(merged);
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
