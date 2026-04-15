package art.arcane.iris.engine.framework;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class GenerationSessionManagerTest {
    @Test
    public void teardownSealMarksRejectedWorkAsExpected() throws Exception {
        GenerationSessionManager manager = new GenerationSessionManager();

        manager.sealAndAwait("close", 1000L, true);

        try {
            manager.acquire("chunk_generate");
        } catch (GenerationSessionException e) {
            assertTrue(e.isExpectedTeardown());
            assertTrue(e.getMessage().contains("during close"));
            return;
        }

        throw new AssertionError("Expected teardown rejection.");
    }

    @Test
    public void sealAndAwaitCompletesWhenOutstandingLeaseReleases() throws Exception {
        GenerationSessionManager manager = new GenerationSessionManager();
        GenerationSessionLease lease = manager.acquire("chunk_generate");
        CountDownLatch latch = new CountDownLatch(1);

        Thread releaser = new Thread(() -> {
            try {
                latch.await(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lease.close();
        });
        releaser.start();
        latch.countDown();

        manager.sealAndAwait("close", 1000L, true);
    }
}
