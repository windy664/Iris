package art.arcane.iris.core.pregenerator.methods;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AsyncPregenMethodConcurrencyCapTest {
    @Test
    public void paperLikeRecommendedCapTracksWorkerThreads() {
        assertEquals(16, AsyncPregenMethod.computePaperLikeRecommendedCap(1));
        assertEquals(32, AsyncPregenMethod.computePaperLikeRecommendedCap(4));
        assertEquals(96, AsyncPregenMethod.computePaperLikeRecommendedCap(12));
        assertEquals(128, AsyncPregenMethod.computePaperLikeRecommendedCap(80));
        assertEquals(128, AsyncPregenMethod.computePaperLikeRecommendedCap(128));
    }

    @Test
    public void foliaRecommendedCapTracksWorkerThreads() {
        assertEquals(64, AsyncPregenMethod.computeFoliaRecommendedCap(1));
        assertEquals(96, AsyncPregenMethod.computeFoliaRecommendedCap(12));
        assertEquals(160, AsyncPregenMethod.computeFoliaRecommendedCap(20));
        assertEquals(192, AsyncPregenMethod.computeFoliaRecommendedCap(80));
    }

    @Test
    public void paperLikeConcurrencyProvisionsForWorldGenThreadBump() {
        assertEquals(32, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(4, 16, 32));
        assertEquals(16, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(4, 16, 16));
        assertEquals(24, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(-1, 16, 24));
        assertEquals(16, AsyncPregenMethod.resolvePaperLikeConcurrencyWorkerThreads(-1, 16, 8));
    }

    @Test
    public void foliaConcurrencyStillUsesBroaderRuntimeCapacity() {
        assertEquals(32, AsyncPregenMethod.resolveFoliaConcurrencyWorkerThreads(4, 16, 32));
        assertEquals(16, AsyncPregenMethod.resolveFoliaConcurrencyWorkerThreads(-1, 16, 12));
    }
}
