package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.object.FloatingObjectFootprint;
import art.arcane.iris.engine.object.IrisObjectRotation;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;

public class MantleFloatingObjectComponentInvertedCountersTest {

    private FloatingObjectFootprint footprint(int lowestSolidKeyY, int highestSolidKeyY, int tallestKx, int tallestKz) throws Exception {
        Constructor<FloatingObjectFootprint> constructor = FloatingObjectFootprint.class.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                long[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                lowestSolidKeyY,
                highestSolidKeyY,
                0,
                0,
                0,
                tallestKx,
                tallestKz,
                99,
                99,
                new long[0]
        );
    }

    @Test
    public void resetObjectCounters_resetsAllInvertedCountersToZero() {
        MantleFloatingObjectComponent.objectsInvertedAttempted.set(5);
        MantleFloatingObjectComponent.objectsInvertedPlaced.set(3);
        MantleFloatingObjectComponent.objectsInvertedSkippedNoFlat.set(2);
        MantleFloatingObjectComponent.objectsInvertedFallbackNoInterior.set(1);
        MantleFloatingObjectComponent.objectsInvertedSkippedShrink.set(4);
        MantleFloatingObjectComponent.objectsInvertedSkippedNullObj.set(7);
        MantleFloatingObjectComponent.writesDroppedAboveBottomTotal.set(11);
        MantleFloatingObjectComponent.writesDroppedBottomOverhangTotal.set(9);

        MantleFloatingObjectComponent.resetObjectCounters();

        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedAttempted.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedPlaced.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedSkippedNoFlat.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedFallbackNoInterior.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedSkippedShrink.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsInvertedSkippedNullObj.get());
        assertEquals(0, MantleFloatingObjectComponent.writesDroppedAboveBottomTotal.get());
        assertEquals(0, MantleFloatingObjectComponent.writesDroppedBottomOverhangTotal.get());
    }

    @Test
    public void resetObjectCounters_alsoResetsExistingCounters_noRegression() {
        MantleFloatingObjectComponent.objectsAttempted.set(99);
        MantleFloatingObjectComponent.objectsPlaced.set(88);

        MantleFloatingObjectComponent.resetObjectCounters();

        assertEquals(0, MantleFloatingObjectComponent.objectsAttempted.get());
        assertEquals(0, MantleFloatingObjectComponent.objectsPlaced.get());
    }

    @Test
    public void invertedBaseY_anchorsOriginalLowestSolidBelowBottomFace() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);

        assertEquals(104, MantleFloatingObjectComponent.invertedBaseY(100, footprint));
    }

    @Test
    public void invertedBaseX_usesTopFootprintAnchor() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);

        assertEquals(106, MantleFloatingObjectComponent.invertedBaseX(100, 8, footprint));
    }

    @Test
    public void invertedBaseZ_mirrorsTopFootprintAnchor() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);

        assertEquals(111, MantleFloatingObjectComponent.invertedBaseZ(100, 8, footprint));
    }

    @Test
    public void invertedBaseX_usesFixedYRotationAnchor() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);
        IrisObjectRotation rotation = IrisObjectRotation.xFlip180WithY(90);

        assertEquals(111, MantleFloatingObjectComponent.invertedBaseX(100, 8, footprint, rotation));
    }

    @Test
    public void invertedBaseZ_usesFixedYRotationAnchor() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);
        IrisObjectRotation rotation = IrisObjectRotation.xFlip180WithY(90);

        assertEquals(110, MantleFloatingObjectComponent.invertedBaseZ(100, 8, footprint, rotation));
    }

    @Test
    public void invertedBaseY_isStableAcrossFixedYRotation() throws Exception {
        FloatingObjectFootprint footprint = footprint(5, 30, 2, 3);
        IrisObjectRotation rotation = IrisObjectRotation.xFlip180WithY(270);

        assertEquals(104, MantleFloatingObjectComponent.invertedBaseY(100, footprint, rotation));
    }
}
