package art.arcane.iris.core.runtime;

import art.arcane.iris.Iris;
import art.arcane.iris.core.ServerConfigurator;
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.volmlib.util.io.IO;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SmokeTestService {
    private static volatile SmokeTestService instance;

    private final SmokeDiagnosticsService diagnostics;

    private SmokeTestService() {
        this.diagnostics = SmokeDiagnosticsService.get();
    }

    public static SmokeTestService get() {
        SmokeTestService current = instance;
        if (current != null) {
            return current;
        }

        synchronized (SmokeTestService.class) {
            if (instance != null) {
                return instance;
            }

            instance = new SmokeTestService();
            return instance;
        }
    }

    public String startCreateSmoke(VolmitSender sender, String dimensionKey, long seed, boolean retainOnFailure) {
        SmokeDiagnosticsService.SmokeRunHandle handle = diagnostics.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.CREATE,
                nextWorldName("create"),
                false,
                true,
                null,
                retainOnFailure
        );
        J.a(() -> executeCreateSmoke(handle, sender, dimensionKey, seed, false, true));
        return handle.runId();
    }

    public String startBenchmarkSmoke(VolmitSender sender, String dimensionKey, long seed, boolean retainOnFailure) {
        SmokeDiagnosticsService.SmokeRunHandle handle = diagnostics.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.BENCHMARK,
                nextWorldName("benchmark"),
                false,
                true,
                null,
                retainOnFailure
        );
        J.a(() -> executeCreateSmoke(handle, sender, dimensionKey, seed, true, true));
        return handle.runId();
    }

    public String startStudioSmoke(VolmitSender sender, String dimensionKey, long seed, String playerName, boolean retainOnFailure) {
        String normalizedPlayer = normalizePlayerName(playerName);
        SmokeDiagnosticsService.SmokeRunHandle handle = diagnostics.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.STUDIO,
                nextWorldName("studio"),
                true,
                normalizedPlayer == null,
                normalizedPlayer,
                retainOnFailure
        );
        J.a(() -> executeStudioSmoke(handle, sender, dimensionKey, seed, normalizedPlayer, retainOnFailure, true));
        return handle.runId();
    }

    public String startFullSmoke(VolmitSender sender, String dimensionKey, long seed, String playerName, boolean retainOnFailure) {
        String normalizedPlayer = normalizePlayerName(playerName);
        SmokeDiagnosticsService.SmokeRunHandle handle = diagnostics.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.FULL,
                nextWorldName("full"),
                false,
                normalizedPlayer == null,
                normalizedPlayer,
                retainOnFailure
        );
        J.a(() -> executeFullSmoke(handle, sender, dimensionKey, seed, normalizedPlayer, retainOnFailure));
        return handle.runId();
    }

    public SmokeDiagnosticsService.SmokeRunReport latest() {
        SmokeDiagnosticsService.SmokeRunReport latest = diagnostics.latest();
        if (latest != null) {
            return latest;
        }

        return diagnostics.latestPersisted();
    }

    public SmokeDiagnosticsService.SmokeRunReport get(String runId) {
        return diagnostics.get(runId);
    }

    public WorldInspection inspectWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        PlatformChunkGenerator provider = IrisToolbelt.access(world);
        boolean studio = provider != null && provider.isStudio();
        boolean engineClosed = false;
        boolean engineFailing = false;
        long generationSessionId = 0L;
        int activeLeases = 0;
        if (provider != null && provider.getEngine() instanceof IrisEngine irisEngine) {
            engineClosed = irisEngine.isClosed();
            engineFailing = irisEngine.isFailing();
            generationSessionId = irisEngine.getGenerationSessionId();
            activeLeases = irisEngine.getGenerationSessions().activeLeases();
        }

        ArrayList<String> datapackFolders = new ArrayList<>();
        File datapacksFolder = ServerConfigurator.resolveDatapacksFolder(world.getWorldFolder());
        datapackFolders.add(datapacksFolder.getAbsolutePath());
        return new WorldInspection(
                world.getName(),
                WorldLifecycleService.get().backendNameForWorld(world.getName()),
                WorldRuntimeControlService.get().backendName(),
                studio,
                engineClosed,
                engineFailing,
                generationSessionId,
                activeLeases,
                List.copyOf(datapackFolders),
                IrisToolbelt.isWorldMaintenanceActive(world)
        );
    }

    private void executeFullSmoke(
            SmokeDiagnosticsService.SmokeRunHandle handle,
            VolmitSender sender,
            String dimensionKey,
            long seed,
            String playerName,
            boolean retainOnFailure
    ) {
        try {
            handle.stage("create");
            executeCreateSmoke(handle, sender, dimensionKey, seed, false, false);
            handle.note("create smoke complete");

            handle.stage("benchmark");
            executeCreateSmoke(handle, sender, dimensionKey, seed, true, false);
            handle.note("benchmark smoke complete");

            handle.stage("studio");
            executeStudioSmoke(handle, sender, dimensionKey, seed, playerName, retainOnFailure, false);
            handle.note("studio smoke complete");

            handle.completeSuccess("cleanup", true);
        } catch (Throwable e) {
            handle.completeFailure("cleanup", e, !retainOnFailure);
        }
    }

    private void executeCreateSmoke(
            SmokeDiagnosticsService.SmokeRunHandle handle,
            VolmitSender sender,
            String dimensionKey,
            long seed,
            boolean benchmark,
            boolean completeHandle
    ) {
        String worldName = nextWorldName(benchmark ? "benchmark" : "create");
        handle.setWorldName(worldName);
        cleanupTransientPrefix("iris-smoke-");
        World world = null;
        PlatformChunkGenerator provider = null;
        boolean cleanupApplied = false;
        try {
            IrisCreator creator = IrisToolbelt.createWorld()
                    .dimension(dimensionKey)
                    .name(worldName)
                    .seed(seed)
                    .sender(sender)
                    .studio(false)
                    .benchmark(benchmark)
                    .studioProgressConsumer((progress, stage) -> handle.stage(mapCreateStage(stage)));
            world = creator.create();
            provider = IrisToolbelt.access(world);
            handle.setLifecycleBackend(WorldLifecycleService.get().backendNameForWorld(world.getName()));
            handle.setRuntimeBackend(WorldRuntimeControlService.get().backendName());
            handle.setDatapackReadiness(creator.getLastDatapackReadinessResult());
            captureGenerationSession(provider, handle);

            if (benchmark) {
                handle.stage("apply_world_rules");
                WorldRuntimeControlService.get().applyStudioWorldRules(world);
            }

            handle.stage("cleanup");
            cleanupWorld(world, worldName);
            cleanupApplied = true;
            if (completeHandle) {
                handle.completeSuccess("cleanup", true);
            }
        } catch (Throwable e) {
            Iris.reportError("Smoke create failed for world \"" + worldName + "\".", e);
            if (!handle.snapshot().isRetainOnFailure()) {
                try {
                    cleanupWorld(world, worldName);
                    cleanupApplied = true;
                } catch (Throwable cleanupError) {
                    Iris.reportError("Smoke cleanup failed for world \"" + worldName + "\".", cleanupError);
                }
            }
            if (completeHandle) {
                handle.completeFailure("cleanup", e, cleanupApplied);
            } else {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }

                throw new RuntimeException(e);
            }
        }
    }

    private void executeStudioSmoke(
            SmokeDiagnosticsService.SmokeRunHandle handle,
            VolmitSender sender,
            String dimensionKey,
            long seed,
            String playerName,
            boolean retainOnFailure,
            boolean completeHandle
    ) {
        String worldName = nextWorldName("studio");
        handle.setWorldName(worldName);
        cleanupTransientPrefix("iris-smoke-");
        World world = null;
        boolean cleanupApplied = false;
        CompletableFuture<StudioOpenCoordinator.StudioOpenResult> future = StudioOpenCoordinator.get().open(
                new StudioOpenCoordinator.StudioOpenRequest(
                        dimensionKey,
                        null,
                        sender,
                        seed,
                        worldName,
                        playerName,
                        false,
                        retainOnFailure,
                        SmokeDiagnosticsService.SmokeRunMode.STUDIO,
                        handle,
                        completeHandle,
                        update -> handle.stage(update.stage()),
                        openedWorld -> {
                        }
                )
        );
        try {
            StudioOpenCoordinator.StudioOpenResult result = future.join();
            world = result == null ? null : result.world();
            handle.stage("cleanup");
            cleanupWorld(world, worldName);
            cleanupApplied = true;
            if (completeHandle) {
                handle.completeSuccess("cleanup", true);
            }
        } catch (Throwable e) {
            if (world != null && !cleanupApplied) {
                try {
                    cleanupWorld(world, worldName);
                    cleanupApplied = true;
                } catch (Throwable cleanupError) {
                    Iris.reportError("Smoke cleanup failed for world \"" + worldName + "\".", cleanupError);
                }
            }
            if (completeHandle && !"failed".equalsIgnoreCase(handle.snapshot().getOutcome())) {
                handle.completeFailure("cleanup", e, cleanupApplied);
            }
            if (!completeHandle) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }

                throw new RuntimeException(e);
            }
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

    private void cleanupWorld(World world, String worldName) {
        if (world != null) {
            PlatformChunkGenerator provider = IrisToolbelt.access(world);
            if (provider != null) {
                provider.close();
            }
            WorldLifecycleService.get().unload(world, false);
        }

        File container = Bukkit.getWorldContainer();
        deleteFolder(new File(container, worldName), worldName);
        deleteFolder(new File(container, worldName + "_nether"), null);
        deleteFolder(new File(container, worldName + "_the_end"), null);
    }

    private void deleteFolder(File folder, String worldName) {
        if (folder == null) {
            return;
        }

        IO.delete(folder);
        if (!folder.exists()) {
            return;
        }

        if (worldName == null) {
            return;
        }

        try {
            Iris.queueWorldDeletionOnStartup(Collections.singleton(worldName));
        } catch (IOException e) {
            Iris.reportError("Failed to queue smoke world deletion for \"" + worldName + "\".", e);
        }
    }

    private void cleanupTransientPrefix(String prefix) {
        File container = Bukkit.getWorldContainer();
        File[] children = container.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            if (!child.getName().startsWith(prefix)) {
                continue;
            }
            if (Bukkit.getWorld(child.getName()) != null) {
                continue;
            }
            IO.delete(child);
        }
    }

    private String nextWorldName(String mode) {
        return "iris-smoke-" + mode + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String normalizePlayerName(String playerName) {
        if (playerName == null) {
            return null;
        }

        String trimmed = playerName.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) {
            return null;
        }

        return trimmed;
    }

    private String mapCreateStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "create_world";
        }

        String normalized = stage.trim().toLowerCase();
        return switch (normalized) {
            case "resolve_dimension", "resolving dimension" -> "resolve_dimension";
            case "prepare_world_pack", "preparing world pack" -> "prepare_world_pack";
            case "install_datapacks", "installing datapacks" -> "install_datapacks";
            case "create_world", "creating world", "world created" -> "create_world";
            default -> normalized.replace(' ', '_');
        };
    }

    public record WorldInspection(
            String worldName,
            String lifecycleBackend,
            String runtimeBackend,
            boolean studio,
            boolean engineClosed,
            boolean engineFailing,
            long generationSessionId,
            int activeLeaseCount,
            List<String> datapackFolders,
            boolean maintenanceActive
    ) {
    }
}
