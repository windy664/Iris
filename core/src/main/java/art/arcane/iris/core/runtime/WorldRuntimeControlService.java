package art.arcane.iris.core.runtime;

import art.arcane.iris.Iris;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.lifecycle.CapabilitySnapshot;
import art.arcane.iris.core.lifecycle.ServerFamily;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.service.BoardSVC;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WorldRuntimeControlService {
    private static volatile WorldRuntimeControlService instance;

    private final CapabilitySnapshot capabilities;
    private final WorldRuntimeControlBackend backend;
    private final String capabilityDescription;

    private WorldRuntimeControlService(CapabilitySnapshot capabilities) {
        this.capabilities = capabilities;
        this.backend = selectBackend(capabilities);
        this.capabilityDescription = "family=" + capabilities.serverFamily().id()
                + ", backend=" + backend.backendName()
                + ", " + backend.describeCapabilities();
    }

    public static WorldRuntimeControlService get() {
        WorldRuntimeControlService current = instance;
        if (current != null) {
            return current;
        }

        synchronized (WorldRuntimeControlService.class) {
            if (instance != null) {
                return instance;
            }

            CapabilitySnapshot capabilities = WorldLifecycleService.get().capabilities();
            instance = new WorldRuntimeControlService(capabilities);
            Iris.info("WorldRuntimeControl capabilities: %s", instance.capabilityDescription);
            return instance;
        }
    }

    public String backendName() {
        return backend.backendName();
    }

    public String capabilityDescription() {
        return capabilityDescription;
    }

    public OptionalLong readDayTime(World world) {
        return backend.readDayTime(world);
    }

    public boolean applyStudioWorldRules(World world) {
        if (world == null) {
            return false;
        }

        Iris.linkMultiverseCore.removeFromConfig(world);
        if (!IrisSettings.get().getStudio().isDisableTimeAndWeather()) {
            return true;
        }

        setBooleanGameRule(world, false, "ADVANCE_WEATHER", "DO_WEATHER_CYCLE", "WEATHER_CYCLE", "doWeatherCycle", "weatherCycle");
        setBooleanGameRule(world, false, "ADVANCE_TIME", "DO_DAYLIGHT_CYCLE", "DAYLIGHT_CYCLE", "doDaylightCycle", "daylightCycle");
        applyNoonTimeLock(world);
        return true;
    }

    public boolean applyNoonTimeLock(World world) {
        if (world == null) {
            return false;
        }

        if (!hasMutableClock(world)) {
            return false;
        }

        OptionalLong currentTime = readDayTime(world);
        if (currentTime.isEmpty()) {
            return false;
        }

        long skipAmount = (6000L - currentTime.getAsLong()) % 24000L;
        if (skipAmount < 0L) {
            skipAmount += 24000L;
        }

        TimeSkipEvent event = new TimeSkipEvent(world, TimeSkipEvent.SkipReason.CUSTOM, skipAmount);
        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager != null) {
            pluginManager.callEvent(event);
        }
        if (event.isCancelled()) {
            return false;
        }

        try {
            boolean written = backend.writeDayTime(world, currentTime.getAsLong() + event.getSkipAmount());
            if (!written) {
                return false;
            }
            backend.syncTime(world);
            return true;
        } catch (Throwable e) {
            Iris.reportError("Runtime time control failed for world \"" + world.getName() + "\".", e);
            return false;
        }
    }

    public CompletableFuture<Chunk> requestChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        return backend.requestChunkAsync(world, chunkX, chunkZ, generate);
    }

    public void prepareGenerator(World world) {
        if (world == null) {
            return;
        }

        try {
            art.arcane.iris.engine.platform.PlatformChunkGenerator provider = art.arcane.iris.core.tools.IrisToolbelt.access(world);
            if (provider == null) {
                return;
            }

            art.arcane.iris.engine.framework.Engine engine = provider.getEngine();
            if (engine == null) {
                return;
            }

            engine.getMantle().getComponents();
            engine.getMantle().getRealRadius();
        } catch (Throwable e) {
            Iris.reportError("Failed to prepare generator state for world \"" + world.getName() + "\".", e);
        }
    }

    public Location resolveEntryAnchor(World world) {
        if (world == null) {
            return null;
        }

        PlatformChunkGenerator provider = IrisToolbelt.access(world);
        return resolveEntryAnchor(world, provider);
    }

    static Location resolveEntryAnchor(World world, PlatformChunkGenerator provider) {
        if (world == null) {
            return null;
        }

        if (provider != null && provider.isStudio()) {
            Location initialSpawn = provider.getInitialSpawnLocation(world);
            if (initialSpawn != null) {
                return initialSpawn.clone();
            }
        }

        Location spawnLocation = world.getSpawnLocation();
        if (spawnLocation != null) {
            return spawnLocation.clone();
        }

        int minY = world.getMinHeight() + 1;
        int y = Math.max(minY, 96);
        return new Location(world, 0.5D, y, 0.5D);
    }

    public CompletableFuture<Location> resolveSafeEntry(World world, Location source) {
        if (world == null || source == null) {
            return CompletableFuture.completedFuture(null);
        }

        int chunkX = source.getBlockX() >> 4;
        int chunkZ = source.getBlockZ() >> 4;
        return requestChunkAsync(world, chunkX, chunkZ, true).thenCompose(chunk -> {
            CompletableFuture<Location> future = new CompletableFuture<>();
            boolean scheduled = J.runRegion(world, chunkX, chunkZ, () -> {
                try {
                    future.complete(findTopSafeLocation(world, source));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
            if (!scheduled) {
                return CompletableFuture.failedFuture(new IllegalStateException("Failed to schedule safe-entry surface resolve for " + world.getName() + "@" + chunkX + "," + chunkZ + "."));
            }

            return future;
        });
    }

    public CompletableFuture<Boolean> teleport(Player player, Location location) {
        if (player == null || location == null) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean scheduled = J.runEntity(player, () -> {
            CompletableFuture<Boolean> teleportFuture = PaperLib.teleportAsync(player, location);
            if (teleportFuture == null) {
                future.complete(false);
                return;
            }

            teleportFuture.whenComplete((success, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                    return;
                }

                if (Boolean.TRUE.equals(success)) {
                    J.runEntity(player, () -> Iris.service(BoardSVC.class).updatePlayer(player));
                    future.complete(true);
                    return;
                }

                future.complete(false);
            });
        });
        if (!scheduled) {
            return CompletableFuture.failedFuture(new IllegalStateException("Failed to schedule teleport for " + player.getName() + "."));
        }

        return future;
    }

    public boolean hasMutableClock(World world) {
        try {
            Object handle = invokeNoArg(world, "getHandle");
            if (handle == null) {
                return false;
            }

            Object dimensionTypeHolder = invokeNoArg(handle, "dimensionTypeRegistration");
            Object dimensionType = unwrapDimensionType(dimensionTypeHolder);
            if (dimensionType == null) {
                return false;
            }

            return !dimensionTypeHasFixedTime(dimensionType);
        } catch (Throwable e) {
            return false;
        }
    }

    private static WorldRuntimeControlBackend selectBackend(CapabilitySnapshot capabilities) {
        ServerFamily family = capabilities.serverFamily();
        if (family.isPaperLike()) {
            return new PaperLikeRuntimeControlBackend(capabilities);
        }

        return new BukkitPublicRuntimeControlBackend(capabilities);
    }

    static Location findTopSafeLocation(World world, Location source) {
        int x = source.getBlockX();
        int z = source.getBlockZ();
        float yaw = source.getYaw();
        float pitch = source.getPitch();

        for (int y : buildSafeLocationScanOrder(world, source)) {
            if (isSafeStandingLocation(world, x, y, z)) {
                return new Location(world, x + 0.5D, y, z + 0.5D, yaw, pitch);
            }
        }

        return null;
    }

    static int[] buildSafeLocationScanOrder(World world, Location source) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int[] scanOrder = new int[maxY - minY + 1];
        int index = 0;

        for (int y = maxY; y >= minY; y--) {
            scanOrder[index++] = y;
        }

        return scanOrder;
    }

    private static boolean isSafeStandingLocation(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
            return false;
        }

        Block below = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Material belowType = below.getType();
        if (!belowType.isSolid()) {
            return false;
        }
        if (Tag.LEAVES.isTagged(belowType)) {
            return false;
        }
        if (belowType == Material.LAVA
                || belowType == Material.MAGMA_BLOCK
                || belowType == Material.FIRE
                || belowType == Material.SOUL_FIRE
                || belowType == Material.CAMPFIRE
                || belowType == Material.SOUL_CAMPFIRE) {
            return false;
        }
        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }
        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private static void setBooleanGameRule(World world, boolean value, String... names) {
        GameRule<Boolean> gameRule = resolveBooleanGameRule(world, names);
        if (gameRule != null) {
            world.setGameRule(gameRule, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Boolean> resolveBooleanGameRule(World world, String... names) {
        if (world == null || names == null || names.length == 0) {
            return null;
        }

        Set<String> candidates = buildRuleNameCandidates(names);
        for (String name : candidates) {
            if (name == null || name.isBlank()) {
                continue;
            }

            try {
                Field field = GameRule.class.getField(name);
                Object value = field.get(null);
                if (value instanceof GameRule<?> gameRule && Boolean.class.equals(gameRule.getType())) {
                    return (GameRule<Boolean>) gameRule;
                }
            } catch (Throwable ignored) {
            }

            try {
                GameRule<?> byName = GameRule.getByName(name);
                if (byName != null && Boolean.class.equals(byName.getType())) {
                    return (GameRule<Boolean>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        String[] availableRules = world.getGameRules();
        if (availableRules == null || availableRules.length == 0) {
            return null;
        }

        Set<String> normalizedCandidates = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                normalizedCandidates.add(normalizeRuleName(candidate));
            }
        }

        for (String availableRule : availableRules) {
            String normalizedAvailable = normalizeRuleName(availableRule);
            if (!normalizedCandidates.contains(normalizedAvailable)) {
                continue;
            }

            try {
                GameRule<?> byName = GameRule.getByName(availableRule);
                if (byName != null && Boolean.class.equals(byName.getType())) {
                    return (GameRule<Boolean>) byName;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Set<String> buildRuleNameCandidates(String... names) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }

            candidates.add(name);
            candidates.add(name.toUpperCase());
            candidates.add(name.toLowerCase());
        }

        return candidates;
    }

    private static String normalizeRuleName(String name) {
        if (name == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toLowerCase(current));
            }
        }
        return builder.toString();
    }

    private static boolean dimensionTypeHasFixedTime(Object dimensionType) throws ReflectiveOperationException {
        Object fixedTimeFlag;
        try {
            fixedTimeFlag = invokeNoArg(dimensionType, "hasFixedTime");
        } catch (NoSuchMethodException ignored) {
            Object fixedTime = invokeNoArg(dimensionType, "fixedTime");
            if (fixedTime instanceof OptionalLong optionalLong) {
                return optionalLong.isPresent();
            }
            if (fixedTime instanceof Optional<?> optional) {
                return optional.isPresent();
            }
            return false;
        }

        return fixedTimeFlag instanceof Boolean && (Boolean) fixedTimeFlag;
    }

    private static Object unwrapDimensionType(Object dimensionTypeHolder) throws ReflectiveOperationException {
        if (dimensionTypeHolder == null) {
            return null;
        }

        Class<?> holderClass = dimensionTypeHolder.getClass();
        if (holderClass.getName().startsWith("net.minecraft.world.level.dimension.")) {
            return dimensionTypeHolder;
        }

        Method valueMethod = holderClass.getMethod("value");
        return valueMethod.invoke(dimensionTypeHolder);
    }

    private static Object invokeNoArg(Object instance, String methodName) throws ReflectiveOperationException {
        Method method = instance.getClass().getMethod(methodName);
        return method.invoke(instance);
    }
}
