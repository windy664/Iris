package art.arcane.iris.core.runtime;

import art.arcane.iris.Iris;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.io.IO;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class StudioOpenCoordinator {
    private static volatile StudioOpenCoordinator instance;

    private final SmokeDiagnosticsService diagnostics;

    private StudioOpenCoordinator() {
        this.diagnostics = SmokeDiagnosticsService.get();
    }

    public static StudioOpenCoordinator get() {
        StudioOpenCoordinator current = instance;
        if (current != null) {
            return current;
        }

        synchronized (StudioOpenCoordinator.class) {
            if (instance != null) {
                return instance;
            }

            instance = new StudioOpenCoordinator();
            return instance;
        }
    }

    public CompletableFuture<StudioOpenResult> open(StudioOpenRequest request) {
        CompletableFuture<StudioOpenResult> future = new CompletableFuture<>();
        J.aBukkit(() -> executeOpen(request, future));
        return future;
    }

    public CompletableFuture<StudioCloseResult> closeProject(IrisProject project) {
        CompletableFuture<StudioCloseResult> future = new CompletableFuture<>();
        J.aBukkit(() -> future.complete(executeClose(project)));
        return future;
    }

    private StudioCloseResult executeClose(IrisProject project) {
        if (project == null) {
            return new StudioCloseResult(null, true, true, false, null, null);
        }

        PlatformChunkGenerator provider = project.getActiveProvider();
        if (provider == null) {
            return new StudioCloseResult(null, true, true, false, null, null);
        }

        World world = provider.getTarget().getWorld().realWorld();
        String worldName = world == null ? provider.getTarget().getWorld().name() : world.getName();
        SmokeDiagnosticsService.SmokeRunHandle handle = diagnostics.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.STUDIO_CLOSE,
                worldName,
                true,
                true,
                null,
                false
        );
        StudioCloseResult result;
        try {
            handle.setRuntimeBackend(WorldRuntimeControlService.get().backendName());
            if (world != null) {
                handle.setLifecycleBackend(WorldLifecycleService.get().backendNameForWorld(world.getName()));
                captureGenerationSession(provider, handle);
            }
            result = closeWorld(provider, worldName, world, true, handle, project);
            handle.setCloseState(result.unloadCompletedLive(), result.folderDeletionCompletedLive(), result.startupCleanupQueued());
            if (result.failureCause() != null) {
                handle.completeFailure("finalize_close", result.failureCause(), result.folderDeletionCompletedLive() || result.startupCleanupQueued());
            } else {
                handle.completeSuccess("finalize_close", result.folderDeletionCompletedLive() || result.startupCleanupQueued());
            }
        } catch (Throwable e) {
            project.setActiveProvider(null);
            handle.completeFailure("finalize_close", e, false);
            result = new StudioCloseResult(worldName, false, false, false, e, handle.runId());
        }

        return result;
    }

    private void executeOpen(StudioOpenRequest request, CompletableFuture<StudioOpenResult> future) {
        boolean ownsHandle = request.runHandle() == null;
        SmokeDiagnosticsService.SmokeRunHandle handle = ownsHandle
                ? diagnostics.beginRun(
                request.mode(),
                request.worldName(),
                true,
                request.playerName() == null || request.playerName().isBlank(),
                request.playerName(),
                request.retainOnFailure()
        )
                : request.runHandle();
        World world = null;
        PlatformChunkGenerator provider = null;
        boolean cleanupApplied = false;
        try {
            updateStage(handle, request, "resolve_dimension", 0.04D);
            if (IrisToolbelt.getDimension(request.dimensionKey()) == null) {
                throw new IrisException("Dimension cannot be found for id " + request.dimensionKey() + ".");
            }

            updateStage(handle, request, "prepare_world_pack", 0.10D);
            cleanupStaleTransientWorlds(request.worldName());

            updateStage(handle, request, "install_datapacks", 0.18D);
            IrisCreator creator = IrisToolbelt.createWorld()
                    .seed(request.seed())
                    .sender(request.sender())
                    .studio(true)
                    .name(request.worldName())
                    .dimension(request.dimensionKey())
                    .studioProgressConsumer((progress, stage) -> updateStage(handle, request, mapCreatorStage(stage), progress));
            world = creator.create();
            provider = IrisToolbelt.access(world);
            if (provider == null) {
                throw new IllegalStateException("Studio runtime provider is unavailable for world \"" + request.worldName() + "\".");
            }

            handle.setLifecycleBackend(WorldLifecycleService.get().backendNameForWorld(world.getName()));
            handle.setRuntimeBackend(WorldRuntimeControlService.get().backendName());
            handle.setDatapackReadiness(creator.getLastDatapackReadinessResult());
            captureGenerationSession(provider, handle);

            updateStage(handle, request, "apply_world_rules", 0.72D);
            WorldRuntimeControlService.get().applyStudioWorldRules(world);

            updateStage(handle, request, "prepare_generator", 0.78D);
            WorldRuntimeControlService.get().prepareGenerator(world);

            Location entryAnchor = WorldRuntimeControlService.get().resolveEntryAnchor(world);
            if (entryAnchor == null) {
                throw new IllegalStateException("Studio entry anchor could not be resolved.");
            }

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(120L);
            updateStage(handle, request, "request_entry_chunk", 0.84D);
            requestEntryChunk(world, entryAnchor, deadline, handle);

            updateStage(handle, request, "resolve_safe_entry", 0.90D);
            Location safeEntry = resolveSafeEntry(world, entryAnchor, deadline);
            if (safeEntry == null) {
                throw new IllegalStateException("Studio safe entry resolution timed out.");
            }

            if (request.playerName() != null && !request.playerName().isBlank()) {
                updateStage(handle, request, "teleport_player", 0.96D);
                Player player = resolvePlayer(request.playerName());
                if (player == null) {
                    throw new IllegalStateException("Player \"" + request.playerName() + "\" is not online.");
                }

                long remaining = Math.max(1000L, deadline - System.currentTimeMillis());
                Boolean teleported = WorldRuntimeControlService.get().teleport(player, safeEntry).get(remaining, TimeUnit.MILLISECONDS);
                if (!Boolean.TRUE.equals(teleported)) {
                    throw new IllegalStateException("Studio teleport did not complete successfully.");
                }
            }

            updateStage(handle, request, "finalize_open", 1.00D);
            if (request.project() != null) {
                request.project().setActiveProvider(provider);
            }
            if (request.openWorkspace() && request.project() != null) {
                request.project().openVSCode(request.sender());
            }
            if (request.onDone() != null) {
                request.onDone().accept(world);
            }

            if (request.completeHandle()) {
                handle.completeSuccess("finalize_open", false);
            } else {
                handle.stage("finalize_open");
            }
            future.complete(new StudioOpenResult(world, handle.runId(), safeEntry, creator.getLastDatapackReadinessResult()));
        } catch (Throwable e) {
            Iris.reportError("Studio open failed for world \"" + request.worldName() + "\".", e);
            if (!request.retainOnFailure()) {
                try {
                    updateStage(handle, request, "cleanup", 1.00D);
                    StudioCloseResult cleanupResult = closeWorld(provider, request.worldName(), world, true, handle, request.project());
                    cleanupApplied = cleanupResult.folderDeletionCompletedLive() || cleanupResult.startupCleanupQueued();
                } catch (Throwable cleanupError) {
                    Iris.reportError("Studio cleanup failed for world \"" + request.worldName() + "\".", cleanupError);
                }
            }
            if (request.completeHandle()) {
                handle.completeFailure("cleanup", e, cleanupApplied);
            } else {
                handle.stage("cleanup", String.valueOf(e.getMessage()));
            }
            future.completeExceptionally(e);
        }
    }

    private void requestEntryChunk(World world, Location entryAnchor, long deadline, SmokeDiagnosticsService.SmokeRunHandle handle) throws Exception {
        int chunkX = entryAnchor.getBlockX() >> 4;
        int chunkZ = entryAnchor.getBlockZ() >> 4;
        handle.setEntryChunk(chunkX, chunkZ);
        long remaining = Math.max(1000L, deadline - System.currentTimeMillis());
        waitForEntryChunk(world, chunkX, chunkZ, deadline, null).get(remaining, TimeUnit.MILLISECONDS);
    }

    private Location resolveSafeEntry(World world, Location entryAnchor, long deadline) throws Exception {
        long remaining = Math.max(1000L, deadline - System.currentTimeMillis());
        return waitForSafeEntry(world, entryAnchor, deadline, null).get(remaining, TimeUnit.MILLISECONDS);
    }

    private StudioCloseResult closeWorld(
            PlatformChunkGenerator provider,
            String worldName,
            World world,
            boolean deleteFolder,
            SmokeDiagnosticsService.SmokeRunHandle handle,
            IrisProject project
    ) {
        Throwable failure = null;
        boolean unloadCompletedLive = world == null || !isWorldFamilyLoaded(worldName);
        boolean folderDeletionCompletedLive = !deleteFolder;
        boolean startupCleanupQueued = false;
        CompletableFuture<Void> closeFuture = provider == null ? CompletableFuture.completedFuture(null) : CompletableFuture.completedFuture(null);

        updateCloseStage(handle, "prepare_close");
        if (world != null) {
            handle.setWorldName(world.getName());
            handle.setLifecycleBackend(WorldLifecycleService.get().backendNameForWorld(world.getName()));
            handle.setRuntimeBackend(WorldRuntimeControlService.get().backendName());
            captureGenerationSession(provider, handle);
        }

        if (world != null) {
            updateCloseStage(handle, "evacuate_players");
            try {
                evacuatePlayers(world);
            } catch (Throwable e) {
                failure = e;
            }
        }

        if (world != null) {
            IrisToolbelt.beginWorldMaintenance(world, "studio-close", true);
        }

        try {
            updateCloseStage(handle, "seal_runtime");
            if (project != null) {
                project.setActiveProvider(null);
            }
            if (provider != null) {
                captureGenerationSession(provider, handle);
                closeFuture = provider.closeAsync();
            }

            updateCloseStage(handle, "request_unload");
            if (worldName != null && !worldName.isBlank()) {
                requestWorldFamilyUnload(worldName);
            }

            updateCloseStage(handle, "await_unload");
            if (worldName != null && !worldName.isBlank()) {
                long unloadDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20L);
                CompletableFuture<Void> unloadFuture = waitForWorldFamilyUnload(worldName, unloadDeadline);
                try {
                    unloadFuture.get(Math.max(1000L, unloadDeadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    unloadCompletedLive = true;
                } catch (TimeoutException e) {
                    unloadCompletedLive = !isWorldFamilyLoaded(worldName);
                } catch (Throwable e) {
                    failure = failure == null ? unwrapFailure(e) : failure;
                }
            }

            try {
                closeFuture.get(20L, TimeUnit.SECONDS);
            } catch (Throwable e) {
                Throwable cause = unwrapFailure(e);
                if (failure == null) {
                    failure = cause;
                }
            }

            if (deleteFolder && worldName != null && !worldName.isBlank()) {
                updateCloseStage(handle, "delete_world_family");
                WorldFamilyDeleteResult deleteResult = deleteWorldFamily(worldName, unloadCompletedLive);
                folderDeletionCompletedLive = deleteResult.liveDeleted();
                startupCleanupQueued = deleteResult.startupCleanupQueued();
            }

            updateCloseStage(handle, "finalize_close");
        } finally {
            if (world != null) {
                IrisToolbelt.endWorldMaintenance(world, "studio-close");
            }
        }

        handle.setCloseState(unloadCompletedLive, folderDeletionCompletedLive, startupCleanupQueued);
        return new StudioCloseResult(worldName, unloadCompletedLive, folderDeletionCompletedLive, startupCleanupQueued, failure, handle.runId());
    }

    private void evacuatePlayers(World world) throws Exception {
        if (world == null) {
            return;
        }

        CompletableFuture<Void> future = J.sfut(() -> {
            IrisToolbelt.evacuate(world);
            return null;
        });
        if (future != null) {
            future.get(10L, TimeUnit.SECONDS);
        }
    }

    private void requestWorldFamilyUnload(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            World familyWorld = Bukkit.getWorld(familyWorldName);
            if (familyWorld == null) {
                continue;
            }

            Iris.linkMultiverseCore.removeFromConfig(familyWorld);
            WorldLifecycleService.get().unload(familyWorld, false);
        }
    }

    private WorldFamilyDeleteResult deleteWorldFamily(String worldName, boolean unloadCompletedLive) {
        if (worldName == null || worldName.isBlank()) {
            return new WorldFamilyDeleteResult(true, false);
        }

        File container = Bukkit.getWorldContainer();
        boolean liveDeleted = true;
        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            File folder = new File(container, familyWorldName);
            if (!folder.exists()) {
                continue;
            }

            try {
                deleteWorldFolderAsync(folder, 40).get(15L, TimeUnit.SECONDS);
            } catch (Throwable e) {
                liveDeleted = false;
                Iris.reportError("Studio folder deletion retries failed for \"" + folder.getAbsolutePath() + "\".", unwrapFailure(e));
            }

            if (folder.exists()) {
                liveDeleted = false;
            }
        }

        if (liveDeleted) {
            return new WorldFamilyDeleteResult(true, false);
        }

        try {
            Iris.queueWorldDeletionOnStartup(Collections.singleton(worldName));
            return new WorldFamilyDeleteResult(false, true);
        } catch (IOException e) {
            if (unloadCompletedLive) {
                Iris.reportError("Failed to queue deferred deletion for world \"" + worldName + "\".", e);
            }
            return new WorldFamilyDeleteResult(false, false);
        }
    }

    private void cleanupStaleTransientWorlds(String worldName) {
        File container = Bukkit.getWorldContainer();
        LinkedHashSet<String> staleWorldNames = TransientWorldCleanupSupport.collectTransientStudioWorldNames(container);
        String requestedBaseName = TransientWorldCleanupSupport.transientStudioBaseWorldName(worldName);
        if (requestedBaseName != null) {
            staleWorldNames.add(requestedBaseName);
        }

        for (String staleWorldName : staleWorldNames) {
            if (Bukkit.getWorld(staleWorldName) != null) {
                continue;
            }

            deleteWorldFamily(staleWorldName, true);
        }
    }

    private void captureGenerationSession(PlatformChunkGenerator provider, SmokeDiagnosticsService.SmokeRunHandle handle) {
        if (provider == null || provider.getEngine() == null) {
            return;
        }

        if (provider.getEngine() instanceof IrisEngine irisEngine) {
            handle.setGenerationSession(irisEngine.getGenerationSessionId(), irisEngine.getGenerationSessions().activeLeases());
        }
    }

    private void updateStage(SmokeDiagnosticsService.SmokeRunHandle handle, StudioOpenRequest request, String stage, double progress) {
        handle.stage(stage);
        if (request.progressConsumer() != null) {
            request.progressConsumer().accept(new StudioOpenProgress(progress, stage));
        }
    }

    private String mapCreatorStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "create_world";
        }

        String normalized = stage.trim().toLowerCase();
        return switch (normalized) {
            case "resolve_dimension", "resolving dimension" -> "resolve_dimension";
            case "prepare_world_pack", "preparing world pack" -> "prepare_world_pack";
            case "install_datapacks", "installing datapacks", "datapacks ready" -> "install_datapacks";
            case "create_world", "creating world", "world created" -> "create_world";
            default -> normalized.replace(' ', '_');
        };
    }

    private CompletableFuture<Void> waitForEntryChunk(World world, int chunkX, int chunkZ, long deadline, Throwable lastFailure) {
        long now = System.currentTimeMillis();
        if (now >= deadline) {
            return CompletableFuture.failedFuture(timeoutFailure("Studio entry chunk request timed out.", lastFailure));
        }

        long attemptTimeout = Math.min(Math.max(1000L, deadline - now), 3000L);
        CompletableFuture<org.bukkit.Chunk> request = withAttemptTimeout(
                WorldRuntimeControlService.get().requestChunkAsync(world, chunkX, chunkZ, true),
                attemptTimeout,
                "Studio entry chunk request attempt timed out."
        );
        return request.handle((chunk, throwable) -> {
            if (throwable == null && world.isChunkLoaded(chunkX, chunkZ)) {
                return CompletableFuture.<Void>completedFuture(null);
            }

            Throwable nextFailure = throwable == null ? lastFailure : unwrapFailure(throwable);
            if (System.currentTimeMillis() >= deadline) {
                return CompletableFuture.<Void>failedFuture(timeoutFailure("Studio entry chunk request timed out.", nextFailure));
            }

            return delayFuture(1000L).thenCompose(ignored -> waitForEntryChunk(world, chunkX, chunkZ, deadline, nextFailure));
        }).thenCompose(next -> next);
    }

    private CompletableFuture<Location> waitForSafeEntry(World world, Location entryAnchor, long deadline, Throwable lastFailure) {
        long now = System.currentTimeMillis();
        if (now >= deadline) {
            return CompletableFuture.failedFuture(timeoutFailure("Studio safe-entry resolution timed out.", lastFailure));
        }

        long attemptTimeout = Math.min(Math.max(1000L, deadline - now), 3000L);
        CompletableFuture<Location> resolve = withAttemptTimeout(
                WorldRuntimeControlService.get().resolveSafeEntry(world, entryAnchor),
                attemptTimeout,
                "Studio safe-entry resolution attempt timed out."
        );
        return resolve.handle((location, throwable) -> {
            if (throwable == null && location != null) {
                return CompletableFuture.completedFuture(location);
            }

            Throwable nextFailure = throwable == null ? lastFailure : unwrapFailure(throwable);
            if (System.currentTimeMillis() >= deadline) {
                return CompletableFuture.<Location>failedFuture(timeoutFailure("Studio safe-entry resolution timed out.", nextFailure));
            }

            return delayFuture(250L).thenCompose(ignored -> waitForSafeEntry(world, entryAnchor, deadline, nextFailure));
        }).thenCompose(next -> next);
    }

    private CompletableFuture<Void> waitForWorldFamilyUnload(String worldName, long deadline) {
        if (worldName == null || !isWorldFamilyLoaded(worldName) || System.currentTimeMillis() >= deadline) {
            return CompletableFuture.completedFuture(null);
        }

        return delayFuture(100L).thenCompose(ignored -> waitForWorldFamilyUnload(worldName, deadline));
    }

    private CompletableFuture<Void> deleteWorldFolderAsync(File folder, int attemptsRemaining) {
        if (folder == null || !folder.exists()) {
            return CompletableFuture.completedFuture(null);
        }

        IO.delete(folder);
        if (!folder.exists()) {
            return CompletableFuture.completedFuture(null);
        }

        if (attemptsRemaining <= 1) {
            return CompletableFuture.failedFuture(new IllegalStateException("World folder still exists after deletion retries: " + folder.getAbsolutePath()));
        }

        return delayFuture(250L).thenCompose(ignored -> deleteWorldFolderAsync(folder, attemptsRemaining - 1));
    }

    private CompletableFuture<Void> delayFuture(long delayMillis) {
        long safeDelay = Math.max(0L, delayMillis);
        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(safeDelay, TimeUnit.MILLISECONDS));
    }

    private <T> CompletableFuture<T> withAttemptTimeout(CompletableFuture<T> source, long timeoutMillis, String message) {
        CompletableFuture<T> future = new CompletableFuture<>();
        source.whenComplete((value, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(unwrapFailure(throwable));
                return;
            }

            future.complete(value);
        });
        delayFuture(timeoutMillis).whenComplete((ignored, throwable) -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException(message));
            }
        });
        return future;
    }

    private IllegalStateException timeoutFailure(String message, Throwable lastFailure) {
        if (lastFailure == null) {
            return new IllegalStateException(message);
        }

        return new IllegalStateException(message, lastFailure);
    }

    private Throwable unwrapFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof CompletionException || cursor instanceof ExecutionException) {
            if (cursor.getCause() == null) {
                break;
            }

            cursor = cursor.getCause();
        }

        return cursor;
    }

    private Player resolvePlayer(String playerName) {
        Player exact = Bukkit.getPlayerExact(playerName);
        if (exact != null) {
            return exact;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }

        return null;
    }

    private void updateCloseStage(SmokeDiagnosticsService.SmokeRunHandle handle, String stage) {
        handle.stage(stage);
    }

    private boolean isWorldFamilyLoaded(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
            if (Bukkit.getWorld(familyWorldName) != null) {
                return true;
            }
        }

        return false;
    }

    public record StudioOpenRequest(
            String dimensionKey,
            IrisProject project,
            VolmitSender sender,
            long seed,
            String worldName,
            String playerName,
            boolean openWorkspace,
            boolean retainOnFailure,
            SmokeDiagnosticsService.SmokeRunMode mode,
            SmokeDiagnosticsService.SmokeRunHandle runHandle,
            boolean completeHandle,
            Consumer<StudioOpenProgress> progressConsumer,
            Consumer<World> onDone
    ) {
        public static StudioOpenRequest studioProject(IrisProject project, VolmitSender sender, long seed, Consumer<StudioOpenProgress> progressConsumer, Consumer<World> onDone) {
            String playerName = sender != null && sender.isPlayer() && sender.player() != null ? sender.player().getName() : null;
            return new StudioOpenRequest(
                    project.getName(),
                    project,
                    sender,
                    seed,
                    "iris-" + UUID.randomUUID(),
                    playerName,
                    true,
                    false,
                    SmokeDiagnosticsService.SmokeRunMode.STUDIO_OPEN,
                    null,
                    true,
                    progressConsumer,
                    onDone
            );
        }
    }

    public record StudioOpenProgress(double progress, String stage) {
    }

    public record StudioOpenResult(World world, String runId, Location entryLocation, DatapackReadinessResult datapackReadiness) {
    }

    public record StudioCloseResult(
            String worldName,
            boolean unloadCompletedLive,
            boolean folderDeletionCompletedLive,
            boolean startupCleanupQueued,
            Throwable failureCause,
            String runId
    ) {
        public boolean successful() {
            return failureCause == null;
        }
    }

    private record WorldFamilyDeleteResult(boolean liveDeleted, boolean startupCleanupQueued) {
    }
}
