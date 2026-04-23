package art.arcane.iris.engine.mantle.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MantleFloatingObjectComponentInvertedCountersTest {

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
}
