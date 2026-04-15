package art.arcane.iris.core.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import art.arcane.iris.Iris;
import art.arcane.volmlib.util.io.IO;
import lombok.Data;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SmokeDiagnosticsService {
    private static volatile SmokeDiagnosticsService instance;

    private final ConcurrentHashMap<String, SmokeRunReport> reports;
    private final AtomicReference<String> latestRunId;
    private final AtomicLong runCounter;
    private final Gson gson;

    private SmokeDiagnosticsService() {
        this.reports = new ConcurrentHashMap<>();
        this.latestRunId = new AtomicReference<>();
        this.runCounter = new AtomicLong(1L);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static SmokeDiagnosticsService get() {
        SmokeDiagnosticsService current = instance;
        if (current != null) {
            return current;
        }

        synchronized (SmokeDiagnosticsService.class) {
            if (instance != null) {
                return instance;
            }

            instance = new SmokeDiagnosticsService();
            return instance;
        }
    }

    public SmokeRunHandle beginRun(SmokeRunMode mode, String worldName, boolean studio, boolean headless, String playerName, boolean retainOnFailure) {
        long ordinal = runCounter.getAndIncrement();
        String runId = String.format("%s-%05d", mode.id(), ordinal);
        SmokeRunReport report = new SmokeRunReport();
        report.setRunId(runId);
        report.setMode(mode.id());
        report.setWorldName(worldName);
        report.setStudio(studio);
        report.setHeadless(headless);
        report.setPlayerName(playerName);
        report.setRetainOnFailure(retainOnFailure);
        report.setStartedAt(System.currentTimeMillis());
        report.setOutcome("running");
        report.setStage("queued");
        report.setLifecycleBackend(art.arcane.iris.core.lifecycle.WorldLifecycleService.get().capabilities().serverFamily().id());
        report.setRuntimeBackend(WorldRuntimeControlService.get().backendName());
        reports.put(runId, report);
        latestRunId.set(runId);
        persist(report);
        return new SmokeRunHandle(report);
    }

    public SmokeRunReport latest() {
        String runId = latestRunId.get();
        if (runId == null) {
            return null;
        }

        return get(runId);
    }

    public SmokeRunReport get(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }

        SmokeRunReport report = reports.get(runId);
        if (report != null) {
            return snapshot(report);
        }

        return load(runId);
    }

    public SmokeRunReport latestPersisted() {
        File latestFile = latestFile();
        if (!latestFile.exists()) {
            return null;
        }

        try {
            return gson.fromJson(IO.readAll(latestFile), SmokeRunReport.class);
        } catch (Throwable e) {
            return null;
        }
    }

    private SmokeRunReport load(String runId) {
        File file = reportFile(runId);
        if (!file.exists()) {
            return null;
        }

        try {
            return gson.fromJson(IO.readAll(file), SmokeRunReport.class);
        } catch (Throwable e) {
            return null;
        }
    }

    private void persist(SmokeRunReport report) {
        if (report == null || !SmokeRunMode.shouldPersist(report.getMode())) {
            return;
        }

        try {
            String json = gson.toJson(report);
            File file = reportFile(report.getRunId());
            IO.writeAll(file, json);
            IO.writeAll(latestFile(), json);
        } catch (Throwable e) {
            Iris.reportError("Failed to persist smoke report \"" + report.getRunId() + "\".", e);
        }
    }

    private SmokeRunReport snapshot(SmokeRunReport report) {
        String json = gson.toJson(report);
        return gson.fromJson(json, SmokeRunReport.class);
    }

    private File reportFile(String runId) {
        if (Iris.instance == null) {
            File root = new File("plugins/Iris/diagnostics/smoke");
            root.mkdirs();
            return new File(root, runId + ".json");
        }

        return Iris.instance.getDataFile("diagnostics", "smoke", runId + ".json");
    }

    private File latestFile() {
        if (Iris.instance == null) {
            File root = new File("plugins/Iris/diagnostics/smoke");
            root.mkdirs();
            return new File(root, "latest.json");
        }

        return Iris.instance.getDataFile("diagnostics", "smoke", "latest.json");
    }

    public enum SmokeRunMode {
        FULL("full", true),
        STUDIO("studio", true),
        CREATE("create", true),
        BENCHMARK("benchmark", true),
        STUDIO_OPEN("studio_open", false),
        STUDIO_CLOSE("studio_close", false);

        private final String id;
        private final boolean persisted;

        SmokeRunMode(String id, boolean persisted) {
            this.id = id;
            this.persisted = persisted;
        }

        public String id() {
            return id;
        }

        static boolean shouldPersist(String id) {
            for (SmokeRunMode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode.persisted;
                }
            }

            return false;
        }
    }

    public final class SmokeRunHandle {
        private final SmokeRunReport report;

        private SmokeRunHandle(SmokeRunReport report) {
            this.report = report;
        }

        public String runId() {
            return report.getRunId();
        }

        public SmokeRunReport snapshot() {
            synchronized (report) {
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                return SmokeDiagnosticsService.this.snapshot(report);
            }
        }

        public void setWorldName(String worldName) {
            synchronized (report) {
                report.setWorldName(worldName);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void setLifecycleBackend(String backend) {
            synchronized (report) {
                report.setLifecycleBackend(backend);
                persist(report);
            }
        }

        public void setRuntimeBackend(String backend) {
            synchronized (report) {
                report.setRuntimeBackend(backend);
                persist(report);
            }
        }

        public void setEntryChunk(int chunkX, int chunkZ) {
            synchronized (report) {
                report.setEntryChunkX(chunkX);
                report.setEntryChunkZ(chunkZ);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void setGenerationSession(long sessionId, int activeLeases) {
            synchronized (report) {
                report.setGenerationSessionId(sessionId);
                report.setGenerationActiveLeases(activeLeases);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void setDatapackReadiness(DatapackReadinessResult readiness) {
            synchronized (report) {
                report.setDatapackReadiness(readiness);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void setCloseState(boolean unloadCompletedLive, boolean folderDeletionCompletedLive, boolean startupCleanupQueued) {
            synchronized (report) {
                report.setCloseUnloadCompletedLive(unloadCompletedLive);
                report.setCloseFolderDeletionCompletedLive(folderDeletionCompletedLive);
                report.setCloseStartupCleanupQueued(startupCleanupQueued);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void note(String text) {
            synchronized (report) {
                ArrayList<String> notes = new ArrayList<>(report.getNotes());
                notes.add(text);
                report.setNotes(List.copyOf(notes));
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void stage(String stage) {
            stage(stage, null);
        }

        public void stage(String stage, String detail) {
            synchronized (report) {
                report.setStage(stage);
                report.setStageDetail(detail);
                report.setElapsedMs(System.currentTimeMillis() - report.getStartedAt());
                persist(report);
            }
        }

        public void completeSuccess(String finalStage, boolean cleanupApplied) {
            synchronized (report) {
                report.setStage(finalStage);
                report.setOutcome("success");
                report.setCleanupApplied(cleanupApplied);
                report.setCompletedAt(System.currentTimeMillis());
                report.setElapsedMs(report.getCompletedAt() - report.getStartedAt());
                persist(report);
            }
        }

        public void completeFailure(String finalStage, Throwable throwable, boolean cleanupApplied) {
            synchronized (report) {
                report.setStage(finalStage);
                report.setOutcome("failed");
                report.setCleanupApplied(cleanupApplied);
                report.setCompletedAt(System.currentTimeMillis());
                report.setElapsedMs(report.getCompletedAt() - report.getStartedAt());
                if (throwable != null) {
                    report.setFailureType(throwable.getClass().getName());
                    report.setFailureMessage(String.valueOf(throwable.getMessage()));
                    report.setFailureChain(failureChain(throwable));
                    report.setFailureStacktrace(stacktrace(throwable));
                }
                persist(report);
            }
        }

        private List<String> failureChain(Throwable throwable) {
            ArrayList<String> chain = new ArrayList<>();
            Throwable cursor = throwable;
            while (cursor != null) {
                chain.add(cursor.getClass().getName() + ": " + String.valueOf(cursor.getMessage()));
                cursor = cursor.getCause();
            }
            return List.copyOf(chain);
        }

        private String stacktrace(Throwable throwable) {
            StringWriter writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            throwable.printStackTrace(printWriter);
            printWriter.flush();
            return writer.toString();
        }
    }

    @Data
    public static final class SmokeRunReport {
        private String runId;
        private String mode;
        private String worldName;
        private String stage;
        private String stageDetail;
        private long startedAt;
        private long completedAt;
        private long elapsedMs;
        private String outcome;
        private String lifecycleBackend;
        private String runtimeBackend;
        private long generationSessionId;
        private int generationActiveLeases;
        private Integer entryChunkX;
        private Integer entryChunkZ;
        private boolean studio;
        private boolean headless;
        private String playerName;
        private boolean retainOnFailure;
        private boolean cleanupApplied;
        private boolean closeUnloadCompletedLive;
        private boolean closeFolderDeletionCompletedLive;
        private boolean closeStartupCleanupQueued;
        private DatapackReadinessResult datapackReadiness;
        private String failureType;
        private String failureMessage;
        private List<String> failureChain = List.of();
        private String failureStacktrace;
        private List<String> notes = List.of();
    }
}
