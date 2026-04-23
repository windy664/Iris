package art.arcane.iris.core.runtime;

import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
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
    public void resolvesSafeEntryImmediatelyWhenColumnIsAllWater() {
        World world = mock(World.class);
        Block stub = mock(Block.class, Mockito.RETURNS_DEEP_STUBS);
        doReturn(-64).when(world).getMinHeight();
        doReturn(320).when(world).getMaxHeight();
        doReturn(true).when(world).isChunkLoaded(0, 0);
        doReturn(62).when(world).getHighestBlockYAt(0, 0);
        doReturn(62).when(world).getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        doReturn(stub).when(world).getBlockAt(anyInt(), anyInt(), anyInt());

        Location source = new Location(world, 0.5D, 62D, 0.5D);
        Location result = WorldRuntimeControlService.findTopSafeLocation(world, source);

        assertNotNull("Safe entry must resolve to a non-null location even for water-only columns", result);
        assertEquals(63, result.getBlockY());
    }

}
