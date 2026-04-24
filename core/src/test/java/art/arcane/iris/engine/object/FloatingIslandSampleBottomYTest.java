package art.arcane.iris.engine.object;

import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.project.noise.NoiseGenerator;
import art.arcane.volmlib.util.math.RNG;
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
            if (b) {
                solidCount++;
            }
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

    @Test
    public void roundedEdgeHeight_zeroFade_removesEdgeWall() {
        assertEquals(0, FloatingIslandSample.roundedEdgeHeight(18, 0.0));
    }

    @Test
    public void roundedEdgeDepth_zeroFade_removesMinimumTailAtEdge() {
        assertEquals(0, FloatingIslandSample.roundedEdgeDepth(10, 20, 1.0, 0.0));
    }

    @Test
    public void roundedEdgeDepth_fullFade_keepsConfiguredDepth() {
        assertEquals(15, FloatingIslandSample.roundedEdgeDepth(10, 20, 0.5, 1.0));
    }

    @Test
    public void carveSolidInterior_preservesOuterShell() {
        boolean[] mask = {false, true, true, true, true, false};
        CNG carve = new CNG(new RNG(1), new NoiseGenerator() {
            @Override
            public double noise(double x) {
                return 1.0;
            }

            @Override
            public double noise(double x, double z) {
                return 1.0;
            }

            @Override
            public double noise(double x, double y, double z) {
                return 1.0;
            }
        }, 1.0, 1);

        int count = FloatingIslandSample.carveSolidInterior(mask, 100, 0, 0, carve, 0.5);

        assertEquals(2, count);
        assertEquals(false, mask[0]);
        assertEquals(true, mask[1]);
        assertEquals(false, mask[2]);
        assertEquals(false, mask[3]);
        assertEquals(true, mask[4]);
        assertEquals(false, mask[5]);
    }

    @Test
    public void hasFootprintNeighborSupport_rejectsSingleIsolatedColumn() {
        CNG footprint = new CNG(new RNG(2)) {
            @Override
            public double noise(double x, double z) {
                return x == 0 && z == 0 ? 1.0 : 0.0;
            }

            @Override
            public double noise(double x, double y, double z) {
                return noise(x, z);
            }
        };

        assertEquals(false, FloatingIslandSample.hasFootprintNeighborSupport(footprint, 0, 0, 0.0));
    }

    @Test
    public void hasFootprintNeighborSupport_acceptsCardinalNeighbor() {
        CNG footprint = new CNG(new RNG(3)) {
            @Override
            public double noise(double x, double z) {
                return (x == 0 && z == 0) || (x == 1 && z == 0) ? 1.0 : 0.0;
            }

            @Override
            public double noise(double x, double y, double z) {
                return noise(x, z);
            }
        };

        assertEquals(true, FloatingIslandSample.hasFootprintNeighborSupport(footprint, 0, 0, 0.0));
    }
}
