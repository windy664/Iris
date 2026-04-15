package art.arcane.iris.core.runtime;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

interface WorldRuntimeControlBackend {
    String backendName();

    String describeCapabilities();

    OptionalLong readDayTime(World world);

    boolean writeDayTime(World world, long dayTime) throws ReflectiveOperationException;

    void syncTime(World world);

    CompletableFuture<Chunk> requestChunkAsync(World world, int chunkX, int chunkZ, boolean generate);
}
