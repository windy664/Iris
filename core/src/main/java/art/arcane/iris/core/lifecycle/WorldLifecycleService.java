package art.arcane.iris.core.lifecycle;

import art.arcane.iris.Iris;
import art.arcane.iris.util.common.scheduling.J;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldLifecycleService {
    private static volatile WorldLifecycleService instance;

    private final CapabilitySnapshot capabilities;
    private final WorldsProviderBackend worldsProviderBackend;
    private final PaperLikeRuntimeBackend paperLikeRuntimeBackend;
    private final BukkitPublicBackend bukkitPublicBackend;
    private final List<WorldLifecycleBackend> backends;
    private final Map<String, String> worldBackendByName;

    public WorldLifecycleService(CapabilitySnapshot capabilities) {
        this.capabilities = capabilities;
        this.worldsProviderBackend = new WorldsProviderBackend(capabilities);
        this.paperLikeRuntimeBackend = new PaperLikeRuntimeBackend(capabilities);
        this.bukkitPublicBackend = new BukkitPublicBackend(capabilities);
        this.backends = List.of(worldsProviderBackend, paperLikeRuntimeBackend, bukkitPublicBackend);
        this.worldBackendByName = new ConcurrentHashMap<>();
    }

    public static WorldLifecycleService get() {
        WorldLifecycleService current = instance;
        if (current != null) {
            return current;
        }

        synchronized (WorldLifecycleService.class) {
            if (instance != null) {
                return instance;
            }

            CapabilitySnapshot capabilities = CapabilitySnapshot.probe();
            instance = new WorldLifecycleService(capabilities);
            Iris.info("WorldLifecycle capabilities: %s", capabilities.describe());
            return instance;
        }
    }

    public CapabilitySnapshot capabilities() {
        return capabilities;
    }

    public CompletableFuture<World> create(WorldLifecycleRequest request) {
        WorldLifecycleBackend backend;
        try {
            backend = selectCreateBackend(request);
        } catch (Throwable e) {
            Iris.reportError("WorldLifecycle create backend selection failed for world=\"" + request.worldName()
                    + "\", caller=" + request.callerKind().name().toLowerCase() + ".", e);
            return CompletableFuture.failedFuture(e);
        }
        Iris.info("WorldLifecycle create: world=%s, caller=%s, backend=%s",
                request.worldName(),
                request.callerKind().name().toLowerCase(),
                backend.backendName());
        return backend.create(request).whenComplete((world, throwable) -> {
            if (throwable != null) {
                Throwable cause = WorldLifecycleSupport.unwrap(throwable);
                Iris.reportError("WorldLifecycle create failed: world=\"" + request.worldName()
                        + "\", caller=" + request.callerKind().name().toLowerCase()
                        + ", backend=" + backend.backendName()
                        + ", family=" + capabilities.serverFamily().id() + ".", cause);
                return;
            }
            if (world != null) {
                worldBackendByName.put(world.getName(), backend.backendName());
            }
        });
    }

    public World createBlocking(WorldLifecycleRequest request) {
        try {
            return create(request).join();
        } catch (CompletionException e) {
            throw new IllegalStateException(WorldLifecycleSupport.unwrap(e));
        }
    }

    public boolean unload(World world, boolean save) {
        if (!J.isPrimaryThread()) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            J.s(() -> {
                try {
                    future.complete(unloadDirect(world, save));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
            return future.join();
        }

        return unloadDirect(world, save);
    }

    private boolean unloadDirect(World world, boolean save) {
        WorldLifecycleBackend backend = selectUnloadBackend(world.getName());
        Iris.info("WorldLifecycle unload: world=%s, backend=%s",
                world.getName(),
                backend.backendName());
        boolean unloaded;
        try {
            unloaded = backend.unload(world, save);
        } catch (Throwable e) {
            Iris.reportError("WorldLifecycle unload failed: world=\"" + world.getName()
                    + "\", backend=" + backend.backendName()
                    + ", family=" + capabilities.serverFamily().id() + ".", e);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e);
        }
        if (unloaded) {
            worldBackendByName.remove(world.getName());
        }
        return unloaded;
    }

    public String backendNameForWorld(String worldName) {
        return selectUnloadBackend(worldName).backendName();
    }

    WorldLifecycleBackend selectCreateBackend(WorldLifecycleRequest request) {
        if (worldsProviderBackend.supports(request, capabilities)) {
            return worldsProviderBackend;
        }

        if (request.studio() && capabilities.serverFamily().isPaperLike()) {
            if (!paperLikeRuntimeBackend.supports(request, capabilities)) {
                throw new IllegalStateException("World lifecycle backend paper_like_runtime is unavailable for studio create on "
                        + capabilities.serverFamily().id() + ": " + capabilities.paperLikeResolution());
            }
            return paperLikeRuntimeBackend;
        }

        for (WorldLifecycleBackend backend : backends) {
            if (backend.supports(request, capabilities)) {
                return backend;
            }
        }

        throw new IllegalStateException("No world lifecycle backend supports request for \"" + request.worldName() + "\".");
    }

    WorldLifecycleBackend selectUnloadBackend(String worldName) {
        String backendName = worldBackendByName.get(worldName);
        if (backendName == null) {
            return bukkitPublicBackend;
        }

        for (WorldLifecycleBackend backend : backends) {
            if (backend.backendName().equals(backendName)) {
                return backend;
            }
        }

        return bukkitPublicBackend;
    }

    void rememberBackend(String worldName, String backendName) {
        worldBackendByName.put(worldName, backendName);
    }
}
