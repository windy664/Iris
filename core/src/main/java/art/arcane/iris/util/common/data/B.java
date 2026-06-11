package art.arcane.iris.util.common.data;

import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;

public class B {
    public static PlatformBlockState getState(String bdxf) {
        return IrisPlatforms.get().registries().block(bdxf);
    }

    public static PlatformBlockState getStateOrNull(String bdxf) {
        return IrisPlatforms.get().registries().blockOrNull(bdxf);
    }

    public static PlatformBlockState getStateOrNull(String bdxf, boolean warn) {
        return IrisPlatforms.get().registries().blockOrNull(bdxf, warn);
    }

    public static KList<PlatformBlockState> getStates(KList<String> find) {
        KList<PlatformBlockState> states = new KList<>(find.size());
        for (String key : find) {
            states.add(getState(key));
        }
        return states;
    }

    public static PlatformBlockState getAirState() {
        return IrisPlatforms.get().registries().air();
    }

    public static PlatformBlockState toDeepSlateOre(PlatformBlockState block, PlatformBlockState ore) {
        return IrisPlatforms.get().registries().deepSlateOre(block, ore);
    }

    public static boolean isAir(PlatformBlockState state) {
        return state == null || state.isAir();
    }

    public static boolean isSolid(PlatformBlockState state) {
        return state != null && state.isSolid();
    }

    public static boolean isFluid(PlatformBlockState state) {
        return state != null && state.isFluid();
    }

    public static boolean isAirOrFluid(PlatformBlockState state) {
        return state == null || state.isAirOrFluid();
    }

    public static boolean isWater(PlatformBlockState state) {
        return state != null && state.isWater();
    }

    public static boolean isWaterLogged(PlatformBlockState state) {
        return state != null && state.isWaterLogged();
    }

    public static boolean isLit(PlatformBlockState state) {
        return state != null && state.isLit();
    }

    public static boolean isUpdatable(PlatformBlockState state) {
        return state != null && state.isUpdatable();
    }

    public static boolean isFoliage(PlatformBlockState state) {
        return state != null && state.isFoliage();
    }

    public static boolean isFoliagePlantable(PlatformBlockState state) {
        return state != null && state.isFoliagePlantable();
    }

    public static boolean isDecorant(PlatformBlockState state) {
        return state != null && state.isDecorant();
    }

    public static boolean isStorage(PlatformBlockState state) {
        return state != null && state.isStorage();
    }

    public static boolean isStorageChest(PlatformBlockState state) {
        return state != null && state.isStorageChest();
    }

    public static boolean isOre(PlatformBlockState state) {
        return state != null && state.isOre();
    }

    public static boolean isDeepSlate(PlatformBlockState state) {
        return state != null && state.isDeepSlate();
    }

    public static boolean isVineBlock(PlatformBlockState state) {
        return state != null && state.isVineBlock();
    }

    public static boolean canPlaceOnto(PlatformBlockState mat, PlatformBlockState onto) {
        return mat != null && onto != null && mat.canPlaceOnto(onto);
    }

    public static boolean matches(PlatformBlockState filter, PlatformBlockState state) {
        return filter != null && state != null && filter.matches(state);
    }
}
