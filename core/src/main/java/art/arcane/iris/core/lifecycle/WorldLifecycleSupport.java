package art.arcane.iris.core.lifecycle;

import art.arcane.iris.Iris;
import art.arcane.iris.core.link.Identifier;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.nms.INMSBinding;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class WorldLifecycleSupport {
    private WorldLifecycleSupport() {
    }

    static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException && invocationTargetException.getCause() != null) {
            return unwrap(invocationTargetException.getCause());
        }
        if (throwable instanceof java.util.concurrent.CompletionException completionException && completionException.getCause() != null) {
            return unwrap(completionException.getCause());
        }
        if (throwable instanceof ExecutionException executionException && executionException.getCause() != null) {
            return unwrap(executionException.getCause());
        }
        return throwable;
    }

    static Object invoke(Method method, Object target, Object... args) throws ReflectiveOperationException {
        return method.invoke(target, args);
    }

    static Object invokeNamed(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    static Object read(Field field, Object target) throws IllegalAccessException {
        return field.get(target);
    }

    static void stageRuntimeConfiguration(String worldName) throws ReflectiveOperationException {
        Object bukkitServer = Bukkit.getServer();
        if (bukkitServer == null) {
            throw new IllegalStateException("Bukkit server is unavailable.");
        }

        Field configurationField = CapabilityResolution.resolveField(bukkitServer.getClass(), "configuration");
        Object rawConfiguration = configurationField.get(bukkitServer);
        if (!(rawConfiguration instanceof YamlConfiguration configuration)) {
            throw new IllegalStateException("CraftServer configuration field is unavailable.");
        }

        ConfigurationSection worldsSection = configuration.getConfigurationSection("worlds");
        if (worldsSection == null) {
            worldsSection = configuration.createSection("worlds");
        }

        ConfigurationSection worldSection = worldsSection.getConfigurationSection(worldName);
        if (worldSection == null) {
            worldSection = worldsSection.createSection(worldName);
        }

        worldSection.set("generator", "Iris:runtime");
    }

    static Object getRuntimeDatapackDimensions(CapabilitySnapshot capabilities) throws ReflectiveOperationException {
        Object worldLoaderContext = read(capabilities.worldLoaderContextField(), capabilities.minecraftServer());
        Method datapackDimensionsMethod = CapabilityResolution.resolveMethod(worldLoaderContext.getClass(), "datapackDimensions", method -> method.getParameterCount() == 0);
        if (datapackDimensionsMethod == null) {
            throw new IllegalStateException("DataLoadContext does not expose datapackDimensions().");
        }
        Object datapackDimensions = datapackDimensionsMethod.invoke(worldLoaderContext);
        if (datapackDimensions == null) {
            throw new IllegalStateException("DataLoadContext.datapackDimensions() returned null.");
        }
        return datapackDimensions;
    }

    static Object getRuntimeServerRegistryAccess(CapabilitySnapshot capabilities) throws ReflectiveOperationException {
        Method registryAccessMethod = capabilities.serverRegistryAccessMethod();
        if (registryAccessMethod == null) {
            throw new IllegalStateException("MinecraftServer does not expose registryAccess().");
        }
        Object registryAccess = registryAccessMethod.invoke(capabilities.minecraftServer());
        if (registryAccess == null) {
            throw new IllegalStateException("MinecraftServer.registryAccess() returned null.");
        }
        return registryAccess;
    }

    static Object getRuntimeLevelStemRegistry(CapabilitySnapshot capabilities) throws ReflectiveOperationException {
        Object datapackDimensions = getRuntimeDatapackDimensions(capabilities);
        Object levelStemRegistryKey = Class.forName("net.minecraft.core.registries.Registries")
                .getField("LEVEL_STEM")
                .get(null);
        Method lookupMethod = CapabilityResolution.resolveMethod(datapackDimensions.getClass(), "lookupOrThrow", method -> method.getParameterCount() == 1);
        if (lookupMethod == null) {
            throw new IllegalStateException("Registry access does not expose lookupOrThrow(...).");
        }
        return lookupMethod.invoke(datapackDimensions, levelStemRegistryKey);
    }

    static Object createRuntimeLevelStemKey(String worldName) throws ReflectiveOperationException {
        String sanitized = worldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_-]", "_");
        String path = "runtime/" + sanitized;
        Identifier identifier = new Identifier("iris", path);
        Object rawIdentifier = Class.forName("net.minecraft.resources.Identifier")
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, identifier.namespace(), identifier.key());
        Object registryKey = Class.forName("net.minecraft.core.registries.Registries")
                .getField("LEVEL_STEM")
                .get(null);
        Method createMethod = Class.forName("net.minecraft.resources.ResourceKey")
                .getMethod("create", registryKey.getClass(), rawIdentifier.getClass());
        return createMethod.invoke(null, registryKey, rawIdentifier);
    }

    static Object createDimensionKey(Object stemKey) throws ReflectiveOperationException {
        Class<?> resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
        Method identifierMethod = CapabilityResolution.resolveMethod(resourceKeyClass, "identifier", method -> method.getParameterCount() == 0);
        Object identifier = identifierMethod.invoke(stemKey);
        Object dimensionRegistryKey = Class.forName("net.minecraft.core.registries.Registries")
                .getField("DIMENSION")
                .get(null);
        Method createMethod = resourceKeyClass.getMethod("create", dimensionRegistryKey.getClass(), identifier.getClass());
        return createMethod.invoke(null, dimensionRegistryKey, identifier);
    }

    static Object resolveRuntimeLevelStem(CapabilitySnapshot capabilities, WorldLifecycleRequest request) throws ReflectiveOperationException {
        return resolveRuntimeLevelStem(capabilities, request, INMS.get());
    }

    static Object resolveRuntimeLevelStem(CapabilitySnapshot capabilities, WorldLifecycleRequest request, INMSBinding binding) throws ReflectiveOperationException {
        ChunkGenerator generator = request.generator();
        if (generator instanceof PlatformChunkGenerator) {
            Object registryAccess = getRuntimeServerRegistryAccess(capabilities);
            try {
                Object levelStem = binding.createRuntimeLevelStem(registryAccess, generator);
                if (levelStem == null) {
                    throw new IllegalStateException("Iris NMS binding returned null runtime LevelStem.");
                }
                return levelStem;
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to create runtime LevelStem from full server registry access for world \"" + request.worldName() + "\".", unwrap(e));
            }
        }

        try {
            Object levelStemRegistry = getRuntimeLevelStemRegistry(capabilities);
            Object overworldKey = Class.forName("net.minecraft.world.level.dimension.LevelStem")
                    .getField("OVERWORLD")
                    .get(null);
            Method getValueMethod = CapabilityResolution.resolveMethod(levelStemRegistry.getClass(), "getValue", method -> method.getParameterCount() == 1);
            if (getValueMethod != null) {
                Object resolved = getValueMethod.invoke(levelStemRegistry, overworldKey);
                if (resolved != null) {
                    return resolved;
                }
            }

            Method getMethod = CapabilityResolution.resolveMethod(levelStemRegistry.getClass(), "get", method -> method.getParameterCount() == 1);
            if (getMethod == null) {
                throw new IllegalStateException("Unable to resolve OVERWORLD LevelStem from registry.");
            }
            Object raw = getMethod.invoke(levelStemRegistry, overworldKey);
            return extractRegistryValue(raw);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to resolve fallback OVERWORLD LevelStem from datapack registry access for world \"" + request.worldName() + "\".", unwrap(e));
        }
    }

    static String runtimeLevelStemRegistrySource(WorldLifecycleRequest request) {
        if (request.generator() instanceof PlatformChunkGenerator) {
            return "full_server_registry";
        }
        return "datapack_level_stem_registry";
    }

    static Object extractRegistryValue(Object raw) throws ReflectiveOperationException {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Optional<?> optional) {
            Object nested = optional.orElse(null);
            if (nested == null) {
                return null;
            }
            return extractRegistryValue(nested);
        }
        Method valueMethod = CapabilityResolution.resolveMethod(raw.getClass(), "value", method -> method.getParameterCount() == 0);
        if (valueMethod != null) {
            return valueMethod.invoke(raw);
        }
        return raw;
    }

    static void applyWorldDataNameAndModInfo(CapabilitySnapshot capabilities, Object worldDataAndGenSettings, String worldName) throws ReflectiveOperationException {
        Method dataMethod = CapabilityResolution.resolveMethod(worldDataAndGenSettings.getClass(), "data", method -> method.getParameterCount() == 0);
        if (dataMethod == null) {
            return;
        }

        Object worldData = dataMethod.invoke(worldDataAndGenSettings);
        if (worldData == null) {
            return;
        }

        Method checkNameMethod = CapabilityResolution.resolveMethod(worldData.getClass(), "checkName", method -> {
            Class<?>[] params = method.getParameterTypes();
            return params.length == 1 && String.class.equals(params[0]);
        });
        if (checkNameMethod != null) {
            checkNameMethod.invoke(worldData, worldName);
        }

        Method getModdedStatusMethod = CapabilityResolution.resolveMethod(capabilities.minecraftServer().getClass(), "getModdedStatus", method -> method.getParameterCount() == 0);
        Method getServerModNameMethod = CapabilityResolution.resolveMethod(capabilities.minecraftServer().getClass(), "getServerModName", method -> method.getParameterCount() == 0);
        if (getModdedStatusMethod == null || getServerModNameMethod == null) {
            return;
        }

        Object modCheck = getModdedStatusMethod.invoke(capabilities.minecraftServer());
        Method shouldReportAsModifiedMethod = CapabilityResolution.resolveMethod(modCheck.getClass(), "shouldReportAsModified", method -> method.getParameterCount() == 0);
        Method setModdedInfoMethod = CapabilityResolution.resolveMethod(worldData.getClass(), "setModdedInfo", method -> {
            Class<?>[] params = method.getParameterTypes();
            return params.length == 2 && String.class.equals(params[0]) && boolean.class.equals(params[1]);
        });
        if (shouldReportAsModifiedMethod == null || setModdedInfoMethod == null) {
            return;
        }

        boolean modified = Boolean.TRUE.equals(shouldReportAsModifiedMethod.invoke(modCheck));
        String modName = (String) getServerModNameMethod.invoke(capabilities.minecraftServer());
        setModdedInfoMethod.invoke(worldData, modName, modified);
    }

    static Object createCurrentWorldDataAndSettings(CapabilitySnapshot capabilities, String worldName) throws ReflectiveOperationException {
        Object settings = read(capabilities.settingsField(), capabilities.minecraftServer());
        Object worldLoaderContext = read(capabilities.worldLoaderContextField(), capabilities.minecraftServer());
        Object levelStemRegistry = getRuntimeLevelStemRegistry(capabilities);
        boolean demo = Boolean.TRUE.equals(capabilities.isDemoMethod().invoke(capabilities.minecraftServer()));
        Object options = read(capabilities.optionsField(), capabilities.minecraftServer());
        Method hasMethod = CapabilityResolution.resolveMethod(options.getClass(), "has", method -> {
            Class<?>[] params = method.getParameterTypes();
            return params.length == 1 && String.class.equals(params[0]);
        });
        boolean bonusChest = hasMethod != null && Boolean.TRUE.equals(hasMethod.invoke(options, "bonusChest"));
        Object dataLoadOutput = capabilities.createNewWorldDataMethod().invoke(null, settings, worldLoaderContext, levelStemRegistry, demo, bonusChest);
        Method cookieMethod = CapabilityResolution.resolveMethod(dataLoadOutput.getClass(), "cookie", method -> method.getParameterCount() == 0);
        if (cookieMethod == null) {
            throw new IllegalStateException("WorldLoader.DataLoadOutput does not expose cookie().");
        }
        Object worldDataAndGenSettings = cookieMethod.invoke(dataLoadOutput);
        applyWorldDataNameAndModInfo(capabilities, worldDataAndGenSettings, worldName);
        return worldDataAndGenSettings;
    }

    static Object createLegacyPrimaryLevelData(CapabilitySnapshot capabilities, Object levelStorageAccess, String worldName) throws ReflectiveOperationException {
        Object levelDataResult = capabilities.paperWorldDataMethod().invoke(null, levelStorageAccess);
        Method fatalErrorMethod = CapabilityResolution.resolveMethod(levelDataResult.getClass(), "fatalError", method -> method.getParameterCount() == 0);
        Method dataTagMethod = CapabilityResolution.resolveMethod(levelDataResult.getClass(), "dataTag", method -> method.getParameterCount() == 0);
        if (fatalErrorMethod != null && Boolean.TRUE.equals(fatalErrorMethod.invoke(levelDataResult))) {
            throw new IllegalStateException("Paper runtime world-data helper reported a fatal error for \"" + worldName + "\".");
        }
        if (dataTagMethod != null && dataTagMethod.invoke(levelDataResult) != null) {
            throw new IllegalStateException("Runtime world \"" + worldName + "\" already contains level data.");
        }

        Object settings = read(capabilities.settingsField(), capabilities.minecraftServer());
        Object worldLoaderContext = read(capabilities.worldLoaderContextField(), capabilities.minecraftServer());
        Object levelStemRegistry = getRuntimeLevelStemRegistry(capabilities);
        boolean demo = Boolean.TRUE.equals(capabilities.isDemoMethod().invoke(capabilities.minecraftServer()));
        Object options = read(capabilities.optionsField(), capabilities.minecraftServer());
        Method hasMethod = CapabilityResolution.resolveMethod(options.getClass(), "has", method -> {
            Class<?>[] params = method.getParameterTypes();
            return params.length == 1 && String.class.equals(params[0]);
        });
        boolean bonusChest = hasMethod != null && Boolean.TRUE.equals(hasMethod.invoke(options, "bonusChest"));
        Object dataLoadOutput = capabilities.createNewWorldDataMethod().invoke(null, settings, worldLoaderContext, levelStemRegistry, demo, bonusChest);
        Method cookieMethod = CapabilityResolution.resolveMethod(dataLoadOutput.getClass(), "cookie", method -> method.getParameterCount() == 0);
        if (cookieMethod == null) {
            throw new IllegalStateException("WorldLoader.DataLoadOutput does not expose cookie().");
        }
        Object primaryLevelData = cookieMethod.invoke(dataLoadOutput);

        Method checkNameMethod = CapabilityResolution.resolveMethod(primaryLevelData.getClass(), "checkName", method -> {
            Class<?>[] params = method.getParameterTypes();
            return params.length == 1 && String.class.equals(params[0]);
        });
        if (checkNameMethod != null) {
            checkNameMethod.invoke(primaryLevelData, worldName);
        }

        Method getModdedStatusMethod = CapabilityResolution.resolveMethod(capabilities.minecraftServer().getClass(), "getModdedStatus", method -> method.getParameterCount() == 0);
        Method getServerModNameMethod = CapabilityResolution.resolveMethod(capabilities.minecraftServer().getClass(), "getServerModName", method -> method.getParameterCount() == 0);
        if (getModdedStatusMethod != null && getServerModNameMethod != null) {
            Object modCheck = getModdedStatusMethod.invoke(capabilities.minecraftServer());
            Method shouldReportAsModifiedMethod = CapabilityResolution.resolveMethod(modCheck.getClass(), "shouldReportAsModified", method -> method.getParameterCount() == 0);
            Method setModdedInfoMethod = CapabilityResolution.resolveMethod(primaryLevelData.getClass(), "setModdedInfo", method -> {
                Class<?>[] params = method.getParameterTypes();
                return params.length == 2 && String.class.equals(params[0]) && boolean.class.equals(params[1]);
            });
            if (shouldReportAsModifiedMethod != null && setModdedInfoMethod != null) {
                boolean modified = Boolean.TRUE.equals(shouldReportAsModifiedMethod.invoke(modCheck));
                String modName = (String) getServerModNameMethod.invoke(capabilities.minecraftServer());
                setModdedInfoMethod.invoke(primaryLevelData, modName, modified);
            }
        }

        return primaryLevelData;
    }

    static Object createLegacyStorageAccess(CapabilitySnapshot capabilities, String worldName) throws ReflectiveOperationException {
        Class<?> levelStorageSourceClass = Class.forName("net.minecraft.world.level.storage.LevelStorageSource");
        Method createDefaultMethod = levelStorageSourceClass.getMethod("createDefault", Path.class);
        Object levelStorageSource = createDefaultMethod.invoke(null, Bukkit.getWorldContainer().toPath());
        Method storageAccessMethod = capabilities.levelStorageAccessMethod();
        if (storageAccessMethod.getParameterCount() == 1) {
            return storageAccessMethod.invoke(levelStorageSource, worldName);
        }
        Object overworldStemKey = Class.forName("net.minecraft.world.level.dimension.LevelStem")
                .getField("OVERWORLD")
                .get(null);
        return storageAccessMethod.invoke(levelStorageSource, worldName, overworldStemKey);
    }

    static void closeLevelStorageAccess(Object levelStorageAccess) {
        if (levelStorageAccess == null) {
            return;
        }
        try {
            Method closeMethod = levelStorageAccess.getClass().getMethod("close");
            closeMethod.invoke(levelStorageAccess);
        } catch (Throwable ignored) {
        }
    }

    static boolean unloadWorld(CapabilitySnapshot capabilities, World world, boolean save) {
        if (world == null) {
            return false;
        }

        CompletableFuture<Boolean> asyncUnload = unloadWorldViaAsyncApi(capabilities, world, save);
        if (asyncUnload != null) {
            return resolveAsyncUnload(asyncUnload);
        }

        try {
            return Bukkit.unloadWorld(world, save);
        } catch (UnsupportedOperationException unsupported) {
            if (capabilities.minecraftServer() == null || capabilities.removeLevelMethod() == null) {
                throw unsupported;
            }
        }

        try {
            if (save) {
                world.save();
            }

            Method getHandleMethod = world.getClass().getMethod("getHandle");
            Object serverLevel = getHandleMethod.invoke(world);
            closeServerLevel(world, serverLevel);
            detachServerLevel(capabilities, serverLevel, world.getName());
            return Bukkit.getWorld(world.getName()) == null;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to unload world \"" + world.getName() + "\" through the selected world lifecycle backend.", unwrap(e));
        }
    }

    private static CompletableFuture<Boolean> unloadWorldViaAsyncApi(CapabilitySnapshot capabilities, World world, boolean save) {
        if (capabilities.unloadWorldAsyncMethod() == null || capabilities.bukkitServer() == null) {
            return null;
        }

        CompletableFuture<Boolean> callbackFuture = new CompletableFuture<>();
        Runnable invokeTask = () -> {
            Consumer<Boolean> callback = result -> callbackFuture.complete(Boolean.TRUE.equals(result));
            try {
                capabilities.unloadWorldAsyncMethod().invoke(capabilities.bukkitServer(), world, save, callback);
            } catch (Throwable e) {
                callbackFuture.completeExceptionally(unwrap(e));
            }
        };

        if (J.isFolia() && !isGlobalTickThread()) {
            CompletableFuture<Void> scheduled = J.sfut(invokeTask);
            if (scheduled == null) {
                callbackFuture.completeExceptionally(new IllegalStateException("Failed to schedule global unload task."));
                return callbackFuture;
            }
            scheduled.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    callbackFuture.completeExceptionally(unwrap(throwable));
                }
            });
            return callbackFuture;
        }

        invokeTask.run();
        return callbackFuture;
    }

    private static boolean resolveAsyncUnload(CompletableFuture<Boolean> asyncUnload) {
        if (J.isPrimaryThread()) {
            if (!asyncUnload.isDone()) {
                return true;
            }

            try {
                return Boolean.TRUE.equals(asyncUnload.join());
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to consume async world unload result.", unwrap(e));
            }
        }

        try {
            return Boolean.TRUE.equals(asyncUnload.get(120, TimeUnit.SECONDS));
        } catch (Throwable e) {
            throw new IllegalStateException("Failed while waiting for async world unload result.", unwrap(e));
        }
    }

    private static void closeServerLevel(World world, Object serverLevel) throws Throwable {
        Method closeMethod = CapabilityResolution.resolveMethod(serverLevel.getClass(), "close", method -> method.getParameterCount() == 0);
        if (closeMethod == null) {
            return;
        }

        if (!J.isFolia()) {
            closeMethod.invoke(serverLevel);
            return;
        }

        Location spawn = world.getSpawnLocation();
        int chunkX = spawn == null ? 0 : spawn.getBlockX() >> 4;
        int chunkZ = spawn == null ? 0 : spawn.getBlockZ() >> 4;
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        boolean scheduled = J.runRegion(world, chunkX, chunkZ, () -> {
            try {
                closeMethod.invoke(serverLevel);
                closeFuture.complete(null);
            } catch (Throwable e) {
                closeFuture.completeExceptionally(unwrap(e));
            }
        });
        if (!scheduled) {
            throw new IllegalStateException("Failed to schedule region close task for world \"" + world.getName() + "\".");
        }
        closeFuture.get(90, TimeUnit.SECONDS);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeWorldFromCraftServerMap(String worldName) throws ReflectiveOperationException {
        Object bukkitServer = Bukkit.getServer();
        if (bukkitServer == null) {
            return;
        }

        Field worldsField = CapabilityResolution.resolveField(bukkitServer.getClass(), "worlds");
        Object rawWorlds = worldsField.get(bukkitServer);
        if (rawWorlds instanceof Map map) {
            map.remove(worldName);
            map.remove(worldName.toLowerCase(Locale.ROOT));
        }
    }

    private static void detachServerLevel(CapabilitySnapshot capabilities, Object serverLevel, String worldName) throws Throwable {
        Runnable detachTask = () -> {
            try {
                capabilities.removeLevelMethod().invoke(capabilities.minecraftServer(), serverLevel);
                removeWorldFromCraftServerMap(worldName);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };

        if (!J.isFolia() || isGlobalTickThread()) {
            detachTask.run();
            return;
        }

        CompletableFuture<Void> detachFuture = J.sfut(detachTask);
        if (detachFuture == null) {
            throw new IllegalStateException("Failed to schedule global detach task for world \"" + worldName + "\".");
        }
        detachFuture.get(15, TimeUnit.SECONDS);
    }

    static boolean isGlobalTickThread() {
        Object server = Bukkit.getServer();
        if (server == null) {
            return false;
        }
        try {
            Method method = server.getClass().getMethod("isGlobalTickThread");
            return Boolean.TRUE.equals(method.invoke(server));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
