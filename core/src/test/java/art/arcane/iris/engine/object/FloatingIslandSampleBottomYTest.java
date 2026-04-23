package art.arcane.iris.engine.object;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FloatingIslandSampleBottomYTest {
    private FloatingIslandSample buildSample(int islandBaseY, boolean[] solidMask) {
        int topIdx = 0;
        int solidCount = 0;
        for (int i = solidMask.length - 1; i >= 0; i--) {
            if (solidMask[i]) {
                topIdx = i;
                break;
            }
        }
        for (boolean b : solidMask) {
            if (b) solidCount++;
        }
        return FloatingIslandSample.constructForTest(islandBaseY, solidMask.length, topIdx, solidCount, solidMask);
    }

    @Test
    public void bottomY_firstMaskTrue_returnsIslandBaseY() {
        boolean[] mask = {true, true, false};
        FloatingIslandSample sample = buildSample(100, mask);
        assertEquals(100, sample.bottomY());
    }

    @Test
    public void bottomY_lowestSolidAtOffset_returnsIslandBaseYPlusOffset() {
        boolean[] mask = {false, false, true, true};
        FloatingIslandSample sample = buildSample(50, mask);
        assertEquals(52, sample.bottomY());
    }

    @Test
    public void bottomY_allFalseMask_returnsNegativeOne() {
        boolean[] mask = {false, false, false};
        FloatingIslandSample sample = buildSample(100, mask);
        assertEquals(-1, sample.bottomY());
    }

    @Test
    public void bottomY_isCached_sameSampleReturnsSameValue() {
        boolean[] mask = {false, true, true};
        FloatingIslandSample sample = buildSample(200, mask);
        int first = sample.bottomY();
        int second = sample.bottomY();
        assertEquals(first, second);
    }

    @Test
    public void solidifyUncarvedInterior_fillsGapsBetweenSolids() {
        boolean[] mask = {false, true, false, false, true, false};
        int count = FloatingIslandSample.solidifyUncarvedInterior(mask);
        assertEquals(4, count);
        assertEquals(false, mask[0]);
        assertEquals(true, mask[1]);
        assertEquals(true, mask[2]);
        assertEquals(true, mask[3]);
        assertEquals(true, mask[4]);
        assertEquals(false, mask[5]);
    }

    @Test
    public void solidifyUncarvedInterior_emptyMaskStaysEmpty() {
        boolean[] mask = {false, false, false};
        int count = FloatingIslandSample.solidifyUncarvedInterior(mask);
        assertEquals(0, count);
        assertEquals(false, mask[0]);
        assertEquals(false, mask[1]);
        assertEquals(false, mask[2]);
    }
}
