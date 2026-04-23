package art.arcane.iris.engine.object;

import art.arcane.iris.engine.mantle.components.IslandObjectPlacer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IslandObjectPlacerAnchorFaceTest {

    private FloatingIslandSample sampleWithBottomAt(int baseY, int bottomOffset) {
        boolean[] mask = new boolean[10];
        mask[bottomOffset] = true;
        mask[9] = true;
        return FloatingIslandSample.constructForTest(baseY, 10, 9, 2, mask);
    }

    @Test
    public void bottomFace_getHighest_inFootprint_returnsSampleBottomY() {
        FloatingIslandSample[] samples = new FloatingIslandSample[256];
        samples[0] = sampleWithBottomAt(100, 0); // bottomY = 100

        IslandObjectPlacer placer = new IslandObjectPlacer(null, samples, 0, 0, 100, IslandObjectPlacer.AnchorFace.BOTTOM);

        int result = placer.getHighest(0, 0, null);
        assertEquals(100, result);
    }

    @Test
    public void bottomFace_getHighest_offFootprint_returnsChunkMinBottomY() {
        FloatingIslandSample[] samples = new FloatingIslandSample[256];
        samples[0] = sampleWithBottomAt(100, 0); // bottomY = 100, only sample

        IslandObjectPlacer placer = new IslandObjectPlacer(null, samples, 0, 0, 100, IslandObjectPlacer.AnchorFace.BOTTOM);

        // No sample at (15, 15) → falls back to chunkMinIslandBottomY = 100
        int result = placer.getHighest(15, 15, null);
        assertEquals(100, result);
    }

    @Test
    public void bottomFace_set_aboveAnchor_dropsWrite_andIncrementsDroppedAboveBottom() {
        FloatingIslandSample[] samples = new FloatingIslandSample[256];
        samples[0] = sampleWithBottomAt(100, 0); // bottomY = 100

        IslandObjectPlacer placer = new IslandObjectPlacer(null, samples, 0, 0, 100, IslandObjectPlacer.AnchorFace.BOTTOM);

        // y=101 >= anchorBottomY=100 → in-footprint but above/at anchor → dropped
        placer.set(0, 101, 0, null);

        assertEquals(1, placer.getWritesAttempted());
        assertEquals(1, placer.getWritesDroppedAboveBottom());
    }

    @Test
    public void topFace_existingConstructor_dropsBelowAnchor_noRegression() {
        FloatingIslandSample[] samples = new FloatingIslandSample[256];
        samples[0] = sampleWithBottomAt(100, 0);
        // No sample at x=1, z=0 (idx=1)

        // Existing single-face constructor defaults to TOP
        IslandObjectPlacer placer = new IslandObjectPlacer(null, samples, 0, 0, 105);

        // Off-footprint column, y=104 <= anchorTopY=105 → dropped below
        placer.set(1, 104, 0, null);

        assertEquals(1, placer.getWritesAttempted());
        assertEquals(1, placer.getWritesDroppedBelow());
    }
}
