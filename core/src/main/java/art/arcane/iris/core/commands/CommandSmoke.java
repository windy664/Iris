package art.arcane.iris.core.commands;

import art.arcane.iris.Iris;
import art.arcane.iris.core.runtime.DatapackReadinessResult;
import art.arcane.iris.core.runtime.SmokeDiagnosticsService;
import art.arcane.iris.core.runtime.SmokeTestService;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.format.Form;

import java.util.List;

@Director(name = "smoke", description = "Run Iris developer smoke diagnostics")
public class CommandSmoke implements DirectorExecutor {
    @Director(description = "Run the full smoke suite", sync = true)
    public void full(
            @Param(name = "dimension", aliases = {"pack"}, description = "The dimension/pack key to validate")
            String dimension,
            @Param(description = "The seed to use", defaultValue = "1337")
            long seed,
            @Param(description = "Optional player validation target or none", defaultValue = "none")
            String player,
            @Param(name = "retain-on-failure", aliases = {"retain"}, description = "Retain the temp world after failure", defaultValue = "false")
            boolean retainOnFailure
    ) {
        String runId = SmokeTestService.get().startFullSmoke(sender(), dimension, seed, player, retainOnFailure);
        announceRun(runId, "full");
    }

    @Director(description = "Run the studio smoke flow", sync = true)
    public void studio(
            @Param(name = "dimension", aliases = {"pack"}, description = "The dimension/pack key to validate")
            String dimension,
            @Param(description = "The seed to use", defaultValue = "1337")
            long seed,
            @Param(description = "Optional player validation target or none", defaultValue = "none")
            String player,
            @Param(name = "retain-on-failure", aliases = {"retain"}, description = "Retain the temp world after failure", defaultValue = "false")
            boolean retainOnFailure
    ) {
        String runId = SmokeTestService.get().startStudioSmoke(sender(), dimension, seed, player, retainOnFailure);
        announceRun(runId, "studio");
    }

    @Director(description = "Run the create/unload smoke flow", sync = true)
    public void create(
            @Param(name = "dimension", aliases = {"pack"}, description = "The dimension/pack key to validate")
            String dimension,
            @Param(description = "The seed to use", defaultValue = "1337")
            long seed,
            @Param(name = "retain-on-failure", aliases = {"retain"}, description = "Retain the temp world after failure", defaultValue = "false")
            boolean retainOnFailure
    ) {
        String runId = SmokeTestService.get().startCreateSmoke(sender(), dimension, seed, retainOnFailure);
        announceRun(runId, "create");
    }

    @Director(description = "Run the benchmark create/unload smoke flow", sync = true)
    public void benchmark(
            @Param(name = "dimension", aliases = {"pack"}, description = "The dimension/pack key to validate")
            String dimension,
            @Param(description = "The seed to use", defaultValue = "1337")
            long seed,
            @Param(name = "retain-on-failure", aliases = {"retain"}, description = "Retain the temp world after failure", defaultValue = "false")
            boolean retainOnFailure
    ) {
        String runId = SmokeTestService.get().startBenchmarkSmoke(sender(), dimension, seed, retainOnFailure);
        announceRun(runId, "benchmark");
    }

    @Director(description = "Show live or persisted smoke status", sync = true)
    public void status(
            @Param(description = "Use latest or a specific run id", defaultValue = "latest")
            String run
    ) {
        SmokeDiagnosticsService.SmokeRunReport report = resolveReport(run);
        if (report == null) {
            sender().sendMessage(C.RED + "No smoke report found for \"" + run + "\".");
            return;
        }

        sendReport(report);
    }

    @Director(description = "Inspect a currently loaded smoke/studio world", sync = true)
    public void inspect(
            @Param(description = "The loaded world name to inspect")
            String world
    ) {
        SmokeTestService.WorldInspection inspection = SmokeTestService.get().inspectWorld(world);
        if (inspection == null) {
            sender().sendMessage(C.RED + "World \"" + world + "\" is not currently loaded.");
            return;
        }

        sender().sendMessage(C.GREEN + "Smoke inspection for " + C.GOLD + inspection.worldName());
        sender().sendMessage(C.GRAY + "Lifecycle backend: " + C.WHITE + inspection.lifecycleBackend());
        sender().sendMessage(C.GRAY + "Runtime backend: " + C.WHITE + inspection.runtimeBackend());
        sender().sendMessage(C.GRAY + "Studio: " + C.WHITE + inspection.studio() + C.GRAY + " | Maintenance active: " + C.WHITE + inspection.maintenanceActive());
        sender().sendMessage(C.GRAY + "Engine closed: " + C.WHITE + inspection.engineClosed() + C.GRAY + " | Engine failing: " + C.WHITE + inspection.engineFailing());
        sender().sendMessage(C.GRAY + "Generation session: " + C.WHITE + inspection.generationSessionId() + C.GRAY + " | Active leases: " + C.WHITE + inspection.activeLeaseCount());
        sender().sendMessage(C.GRAY + "Datapack folders: " + C.WHITE + joinList(inspection.datapackFolders()));
    }

    private void announceRun(String runId, String mode) {
        sender().sendMessage(C.GREEN + "Started " + C.GOLD + mode + C.GREEN + " smoke run " + C.GOLD + runId + C.GREEN + ".");
        sender().sendMessage(C.GREEN + "Use " + C.GOLD + "/iris developer smoke status run=" + runId + C.GREEN + " to monitor progress.");
        sender().sendMessage(C.GREEN + "Latest report: " + C.GOLD + latestReportPath());
    }

    private SmokeDiagnosticsService.SmokeRunReport resolveReport(String run) {
        if (run == null || run.isBlank() || run.equalsIgnoreCase("latest")) {
            return SmokeTestService.get().latest();
        }

        return SmokeTestService.get().get(run);
    }

    private void sendReport(SmokeDiagnosticsService.SmokeRunReport report) {
        String elapsed = Form.duration(Math.max(0L, report.getElapsedMs()), 0);
        sender().sendMessage(C.GREEN + "Smoke run " + C.GOLD + report.getRunId() + C.GREEN + " (" + C.GOLD + report.getMode() + C.GREEN + ")");
        sender().sendMessage(C.GRAY + "World: " + C.WHITE + fallback(report.getWorldName()) + C.GRAY + " | Outcome: " + C.WHITE + fallback(report.getOutcome()));
        sender().sendMessage(C.GRAY + "Stage: " + C.WHITE + fallback(report.getStage()) + C.GRAY + " | Elapsed: " + C.WHITE + elapsed);
        if (report.getStageDetail() != null && !report.getStageDetail().isBlank()) {
            sender().sendMessage(C.GRAY + "Stage detail: " + C.WHITE + report.getStageDetail());
        }
        sender().sendMessage(C.GRAY + "Lifecycle backend: " + C.WHITE + fallback(report.getLifecycleBackend()));
        sender().sendMessage(C.GRAY + "Runtime backend: " + C.WHITE + fallback(report.getRuntimeBackend()));
        sender().sendMessage(C.GRAY + "Generation session: " + C.WHITE + report.getGenerationSessionId() + C.GRAY + " | Active leases: " + C.WHITE + report.getGenerationActiveLeases());
        if (report.getEntryChunkX() != null && report.getEntryChunkZ() != null) {
            sender().sendMessage(C.GRAY + "Entry chunk: " + C.WHITE + report.getEntryChunkX() + "," + report.getEntryChunkZ());
        }
        sender().sendMessage(C.GRAY + "Headless: " + C.WHITE + report.isHeadless() + C.GRAY + " | Player: " + C.WHITE + fallback(report.getPlayerName()));
        sender().sendMessage(C.GRAY + "Retain on failure: " + C.WHITE + report.isRetainOnFailure() + C.GRAY + " | Cleanup applied: " + C.WHITE + report.isCleanupApplied());
        sendDatapackReadiness(report.getDatapackReadiness());
        if (!report.getNotes().isEmpty()) {
            sender().sendMessage(C.GRAY + "Notes: " + C.WHITE + joinList(report.getNotes()));
        }
        if (report.getFailureType() != null && !report.getFailureType().isBlank()) {
            sender().sendMessage(C.RED + "Failure: " + report.getFailureType() + C.GRAY + " - " + C.WHITE + fallback(report.getFailureMessage()));
            if (!report.getFailureChain().isEmpty()) {
                sender().sendMessage(C.RED + "Failure chain: " + C.WHITE + joinList(report.getFailureChain()));
            }
        }
    }

    private void sendDatapackReadiness(DatapackReadinessResult readiness) {
        if (readiness == null) {
            return;
        }

        sender().sendMessage(C.GRAY + "Datapack pack key: " + C.WHITE + fallback(readiness.getRequestedPackKey()));
        sender().sendMessage(C.GRAY + "Datapack folders: " + C.WHITE + joinList(readiness.getResolvedDatapackFolders()));
        sender().sendMessage(C.GRAY + "External datapack result: " + C.WHITE + fallback(readiness.getExternalDatapackInstallResult()));
        sender().sendMessage(C.GRAY + "Verification passed: " + C.WHITE + readiness.isVerificationPassed() + C.GRAY + " | Restart required: " + C.WHITE + readiness.isRestartRequired());
        if (!readiness.getMissingPaths().isEmpty()) {
            sender().sendMessage(C.RED + "Missing datapack paths: " + C.WHITE + joinList(readiness.getMissingPaths()));
        }
    }

    private String latestReportPath() {
        if (Iris.instance == null) {
            return "plugins/Iris/diagnostics/smoke/latest.json";
        }

        return Iris.instance.getDataFile("diagnostics", "smoke", "latest.json").getAbsolutePath();
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }

        return String.join(", ", values);
    }

    private String fallback(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }

        return value;
    }
}
