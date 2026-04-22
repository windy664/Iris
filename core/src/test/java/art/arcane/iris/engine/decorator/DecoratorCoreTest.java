package art.arcane.iris.engine.decorator;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDecorationPart;
import art.arcane.iris.engine.object.IrisDecorator;
import art.arcane.volmlib.util.math.RNG;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DecoratorCoreTest {

    @Test
    public void partSeed_differsByPartOrdinal() {
        long base = 123456789L;
        long s0 = DecoratorCore.partSeed(base, 0);
        long s1 = DecoratorCore.partSeed(base, 1);
        long s2 = DecoratorCore.partSeed(base, 2);
        assertNotEquals(s0, s1);
        assertNotEquals(s1, s2);
    }

    @Test
    public void partSeed_isDeterministic() {
        long base = 987654321L;
        assertEquals(DecoratorCore.partSeed(base, 0), DecoratorCore.partSeed(base, 0));
        assertEquals(DecoratorCore.partSeed(base, 3), DecoratorCore.partSeed(base, 3));
    }

    @Test
    public void placeOpts_resetClearsAllFields() {
        DecoratorCore.PlaceOpts opts = DecoratorCore.SCRATCH_OPTS.get();
        opts.caveSkipFluid = true;
        opts.underwater = true;
        opts.fluidHeight = 99;
        opts.reset();
        assertFalse(opts.caveSkipFluid);
        assertFalse(opts.underwater);
        assertEquals(0, opts.fluidHeight);
    }

    @Test
    public void scratchOpts_sameInstanceReturnedWithinThread() {
        DecoratorCore.PlaceOpts a = DecoratorCore.SCRATCH_OPTS.get();
        DecoratorCore.PlaceOpts b = DecoratorCore.SCRATCH_OPTS.get();
        assertSame(a, b);
    }

    @Test
    public void pickDecorator_emptyBucket_returnsNull() {
        IrisBiome biome = mock(IrisBiome.class);
        IrisData data = mock(IrisData.class);
        when(biome.getDecoratorBucket(IrisDecorationPart.NONE)).thenReturn(new IrisDecorator[0]);

        IrisDecorator result = DecoratorCore.pickDecorator(
                biome, IrisDecorationPart.NONE, new RNG(1L), new RNG(2L), data, 0.0, 0.0);
        assertNull(result);
    }

    @Test
    public void pickDecorator_nonePassChanceGate_returnsNull() {
        IrisBiome biome = mock(IrisBiome.class);
        IrisData data = mock(IrisData.class);
        IrisDecorator d = mock(IrisDecorator.class);
        when(d.passesChanceGate(any(RNG.class), anyDouble(), anyDouble(), any(IrisData.class))).thenReturn(false);
        when(biome.getDecoratorBucket(IrisDecorationPart.NONE)).thenReturn(new IrisDecorator[]{d});

        IrisDecorator result = DecoratorCore.pickDecorator(
                biome, IrisDecorationPart.NONE, new RNG(1L), new RNG(2L), data, 0.0, 0.0);
        assertNull(result);
    }

    @Test
    public void pickDecorator_singleCandidate_alwaysReturnsThat() {
        IrisBiome biome = mock(IrisBiome.class);
        IrisData data = mock(IrisData.class);
        IrisDecorator d = mock(IrisDecorator.class);
        when(d.passesChanceGate(any(RNG.class), anyDouble(), anyDouble(), any(IrisData.class))).thenReturn(true);
        when(biome.getDecoratorBucket(IrisDecorationPart.NONE)).thenReturn(new IrisDecorator[]{d});

        RNG gRNG = new RNG(42L);
        for (int t = 0; t < 50; t++) {
            IrisDecorator result = DecoratorCore.pickDecorator(
                    biome, IrisDecorationPart.NONE, gRNG, new RNG(t * 13L + 7), data, 0.0, 0.0);
            assertSame(d, result);
        }
    }

    @Test
    public void pickDecorator_multiplePassingCandidates_selectsUniformly() {
        IrisBiome biome = mock(IrisBiome.class);
        IrisData data = mock(IrisData.class);

        int n = 4;
        IrisDecorator[] decorators = new IrisDecorator[n];
        for (int i = 0; i < n; i++) {
            IrisDecorator d = mock(IrisDecorator.class);
            when(d.passesChanceGate(any(RNG.class), anyDouble(), anyDouble(), any(IrisData.class))).thenReturn(true);
            decorators[i] = d;
        }
        when(biome.getDecoratorBucket(IrisDecorationPart.NONE)).thenReturn(decorators);

        RNG gRNG = new RNG(99L);
        int[] counts = new int[n];
        int trials = 2000;

        for (int t = 0; t < trials; t++) {
            IrisDecorator picked = DecoratorCore.pickDecorator(
                    biome, IrisDecorationPart.NONE, gRNG, new RNG(t * 31L + 3), data, 0.0, 0.0);
            assertNotNull(picked);
            for (int i = 0; i < n; i++) {
                if (picked == decorators[i]) {
                    counts[i]++;
                    break;
                }
            }
        }

        double expected = trials / (double) n;
        for (int i = 0; i < n; i++) {
            double deviation = Math.abs(counts[i] - expected) / expected;
            assertTrue("Decorator " + i + " selected " + counts[i] + " times; expected ~" + (int) expected
                    + " (deviation " + String.format("%.0f%%", deviation * 100) + ")", deviation < 0.20);
        }
    }
}
