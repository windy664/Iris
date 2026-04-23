package art.arcane.iris.engine.mantle.components;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class MantleObjectComponentCaveExposureTest {

    @Test
    public void test_resolveSurfaceObjectExclusionRadius_largeObject_coversFullFootprint() {
        int radius = MantleObjectComponent.computeSurfaceExclusionRadius(24, 0, 0);
        assertTrue("Expected radius >= 12 for a 24x24 object but got " + radius, radius >= 12);
    }

    @Test
    public void test_resolveSurfaceObjectExclusionRadius_withTranslateOffset_includesOffset() {
        int radius = MantleObjectComponent.computeSurfaceExclusionRadius(16, 5, 3);
        int expected = 8 + 5 + 3 + 1;
        assertTrue("Expected radius >= " + expected + " for 16x16 object with translate offsets 5+3 but got " + radius, radius >= expected);
    }

    @Test
    public void test_resolveSurfaceObjectExclusionRadius_smallObject_atLeastOne() {
        int radius = MantleObjectComponent.computeSurfaceExclusionRadius(2, 0, 0);
        assertTrue("Expected radius >= 1 for a 2x2 object but got " + radius, radius >= 1);
    }

    @Test
    public void test_resolveSurfaceObjectExclusionRadius_nullLike_returnsOne() {
        int radius = MantleObjectComponent.computeSurfaceExclusionRadius(0, 0, 0);
        assertTrue("Expected radius >= 1 for zero-dimension object but got " + radius, radius >= 1);
    }
}
