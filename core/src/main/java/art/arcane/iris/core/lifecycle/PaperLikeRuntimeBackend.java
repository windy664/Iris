package art.arcane.iris.core.lifecycle;

import art.arcane.iris.Iris;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class PaperLikeRuntimeBackend implements WorldLifecycleBackend {
    private final CapabilitySnapshot capabilities;

    PaperLikeRuntimeBackend(CapabilitySnapshot capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public boolean supports(WorldLifecycleRequest request, CapabilitySnapshot capabilities) {
        return request.studio()
                && capabilities.serverFamily().isPaperLike()
                && capabilities.hasPaperLikeRuntime();
    }

    @Override
    public CompletableFuture<World> create(WorldLifecycleRequest request) {
        Object legacyStorageAccess = null;
        try {
            World existing = Bukkit.getWorld(request.worldName());
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }

            if (request.generator() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Runtime world creation requires a non-null chunk generator."));
            }

            WorldLifecycleStaging.stageGenerator(request.worldName(), request.generator(), request.biomeProvider());
            WorldLifecycleSupport.stageRuntimeConfiguration(request.worldName());

            Iris.info("WorldLifecycle runtime LevelStem: world=" + request.worldName()
                    + ", backend=paper_like_runtime, flavor=" + capabilities.paperLikeFlavor().name().toLowerCase(Locale.ROOT)
                    + ", registrySource=" + WorldLifecycleSupport.runtimeLevelStemRegistrySource(request));
            Object levelStem = WorldLifecycleSupport.resolveRuntimeLevelStem(capabilities, request);
            Object stemKey = WorldLifecycleSupport.createRuntimeLevelStemKey(request.worldName());

            if (capabilities.paperLikeFlavor() == CapabilitySnapshot.PaperLikeFlavor.CURRENT_INFO_AND_DATA) {
                Object dimensionKey = WorldLifecycleSupport.createDimensionKey(stemKey);
                Object loadedWorldData = capabilities.paperWorldDataMethod().invoke(null, capabilities.minecraftServer(), dimensionKey, request.worldName());
                Object worldLoadingInfo = capabilities.worldLoadingInfoConstructor().newInstance(request.environment(), stemKey, dimensionKey, true);
                Object worldLoadingInfoAndData = capabilities.worldLoadingInfoAndDataConstructor().newInstance(worldLoadingInfo, loadedWorldData);
                Object worldDataAndGenSettings = WorldLifecycleSupport.createCurrentWorldDataAndSettings(capabilities, request.worldName());
                capabilities.createLevelMethod().invoke(capabilities.minecraftServer(), levelStem, worldLoadingInfoAndData, worldDataAndGenSettings);
            } else {
                legacyStorageAccess = WorldLifecycleSupport.createLegacyStorageAccess(capabilities, request.worldName());
                Object primaryLevelData = WorldLifecycleSupport.createLegacyPrimaryLevelData(capabilities, legacyStorageAccess, request.worldName());
                Object worldLoadingInfo = capabilities.worldLoadingInfoConstructor().newInstance(0, request.worldName(), request.environment().name().toLowerCase(Locale.ROOT), stemKey, true);
                capabilities.createLevelMethod().invoke(capabilities.minecraftServer(), levelStem, worldLoadingInfo, legacyStorageAccess, primaryLevelData);
            }

            World loadedWorld = Bukkit.getWorld(request.worldName());
            if (loadedWorld == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Paper-like runtime backend did not load world \"" + request.worldName() + "\"."));
            }

            return CompletableFuture.completedFuture(loadedWorld);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(WorldLifecycleSupport.unwrap(e));
        } finally {
            WorldLifecycleStaging.clearGenerator(request.worldName());
            WorldLifecycleSupport.closeLevelStorageAccess(legacyStorageAccess);
        }
    }

    @Override
    public boolean unload(World world, boolean save) {
        return WorldLifecycleSupport.unloadWorld(capabilities, world, save);
    }

    @Override
    public String backendName() {
        return "paper_like_runtime";
    }

    @Override
    public String describeSelectionReason() {
        return "server family " + capabilities.serverFamily().id() + " exposes paper-like runtime world lifecycle capabilities";
    }
}
