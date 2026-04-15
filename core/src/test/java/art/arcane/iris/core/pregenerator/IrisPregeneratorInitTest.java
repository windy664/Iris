package art.arcane.iris.core.pregenerator;

import art.arcane.iris.core.IrisSettings;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.math.Position2;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class IrisPregeneratorInitTest {
    @Test
    public void initDoesNotSaveBeforeGenerationStarts() throws Exception {
        IrisSettings previousSettings = IrisSettings.settings;
        IrisSettings.settings = new IrisSettings();
        TrackingPregeneratorMethod method = new TrackingPregeneratorMethod();
        PregenTask task = PregenTask.builder()
                .center(new Position2(0, 0))
                .radiusX(16)
                .radiusZ(16)
                .build();
        try {
            IrisPregenerator pregenerator = new IrisPregenerator(task, method, new NoOpPregenListener());
            Method initMethod = IrisPregenerator.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);

            initMethod.invoke(pregenerator);

            assertEquals(1, method.initCalls.get());
            assertEquals(0, method.saveCalls.get());
        } finally {
            IrisSettings.settings = previousSettings;
        }
    }

    private static final class TrackingPregeneratorMethod implements PregeneratorMethod {
        private final AtomicInteger initCalls = new AtomicInteger();
        private final AtomicInteger saveCalls = new AtomicInteger();

        @Override
        public void init() {
            initCalls.incrementAndGet();
        }

        @Override
        public void close() {
        }

        @Override
        public void save() {
            saveCalls.incrementAndGet();
        }

        @Override
        public boolean supportsRegions(int x, int z, PregenListener listener) {
            return false;
        }

        @Override
        public String getMethod(int x, int z) {
            return "test";
        }

        @Override
        public void generateRegion(int x, int z, PregenListener listener) {
        }

        @Override
        public void generateChunk(int x, int z, PregenListener listener) {
        }

        @Override
        public Mantle getMantle() {
            return null;
        }
    }

    private static final class NoOpPregenListener implements PregenListener {
        @Override
        public void onTick(double chunksPerSecond, double chunksPerMinute, double regionsPerMinute, double percent, long generated, long totalChunks, long chunksRemaining, long eta, long elapsed, String method, boolean cached) {
        }

        @Override
        public void onChunkGenerating(int x, int z) {
        }

        @Override
        public void onChunkGenerated(int x, int z, boolean cached) {
        }

        @Override
        public void onRegionGenerated(int x, int z) {
        }

        @Override
        public void onRegionGenerating(int x, int z) {
        }

        @Override
        public void onChunkCleaned(int x, int z) {
        }

        @Override
        public void onRegionSkipped(int x, int z) {
        }

        @Override
        public void onNetworkStarted(int x, int z) {
        }

        @Override
        public void onNetworkFailed(int x, int z) {
        }

        @Override
        public void onNetworkReclaim(int revert) {
        }

        @Override
        public void onNetworkGeneratedChunk(int x, int z) {
        }

        @Override
        public void onNetworkDownloaded(int x, int z) {
        }

        @Override
        public void onClose() {
        }

        @Override
        public void onSaving() {
        }

        @Override
        public void onChunkExistsInRegionGen(int x, int z) {
        }
    }
}
