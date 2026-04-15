package art.arcane.iris.core.runtime;

import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class WorldRuntimeControlServiceSafeEntryTest {
    @Test
    public void resolvesStudioEntryAnchorFromGeneratorInsteadOfMutableWorldSpawn() {
        World world = mock(World.class);
        PlatformChunkGenerator provider = mock(PlatformChunkGenerator.class);
        Location initialSpawn = new Location(world, 0.5D, 96D, 0.5D);
        Location mutableWorldSpawn = new Location(world, 128.5D, 80D, -64.5D);

        doReturn(true).when(provider).isStudio();
        doReturn(initialSpawn).when(provider).getInitialSpawnLocation(world);
        doReturn(mutableWorldSpawn).when(world).getSpawnLocation();

        Location resolved = WorldRuntimeControlService.resolveEntryAnchor(world, provider);

        assertEquals(initialSpawn, resolved);
    }

    @Test
    public void fallsBackToWorldSpawnWhenGeneratorIsNotStudio() {
        World world = mock(World.class);
        PlatformChunkGenerator provider = mock(PlatformChunkGenerator.class);
        Location mutableWorldSpawn = new Location(world, 128.5D, 80D, -64.5D);

        doReturn(false).when(provider).isStudio();
        doReturn(mutableWorldSpawn).when(world).getSpawnLocation();

        Location resolved = WorldRuntimeControlService.resolveEntryAnchor(world, provider);

        assertEquals(mutableWorldSpawn, resolved);
    }

    @Test
    public void scansFromRuntimeSurfaceBeforeFallingThroughRemainingWorldHeight() {
        World world = mock(World.class);

        doReturn(0).when(world).getMinHeight();
        doReturn(256).when(world).getMaxHeight();
        doReturn(179).when(world).getHighestBlockYAt(0, 0);

        int[] scanOrder = WorldRuntimeControlService.buildSafeLocationScanOrder(world, new Location(world, 0.5D, 96D, 0.5D));

        assertEquals(180, scanOrder[0]);
        assertEquals(179, scanOrder[1]);
        assertEquals(1, scanOrder[179]);
        assertEquals(181, scanOrder[180]);
        assertEquals(254, scanOrder[scanOrder.length - 1]);
    }
}
