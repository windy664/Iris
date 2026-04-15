package art.arcane.iris.core.runtime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmokeDiagnosticsServiceCloseStateTest {
    @Test
    public void closeStateIsPersistedIntoRunSnapshot() {
        SmokeDiagnosticsService service = SmokeDiagnosticsService.get();
        SmokeDiagnosticsService.SmokeRunHandle handle = service.beginRun(
                SmokeDiagnosticsService.SmokeRunMode.STUDIO_CLOSE,
                "iris-test-world",
                true,
                true,
                null,
                false
        );

        handle.setCloseState(true, false, true);

        SmokeDiagnosticsService.SmokeRunReport report = handle.snapshot();
        assertTrue(report.isCloseUnloadCompletedLive());
        assertFalse(report.isCloseFolderDeletionCompletedLive());
        assertTrue(report.isCloseStartupCleanupQueued());
    }
}
