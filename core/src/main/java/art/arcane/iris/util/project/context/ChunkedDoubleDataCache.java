package art.arcane.iris.util.project.context;

import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.utility.ChunkFillableDoubleStream2D;
import art.arcane.volmlib.util.documentation.BlockCoordinates;

import java.util.Arrays;
import java.util.concurrent.Executor;

public class ChunkedDoubleDataCache {
    private final int x;
    private final int z;
    private final ProceduralStream<Double> stream;
    private final boolean cache;
    private final double[] data;

    @BlockCoordinates
    public ChunkedDoubleDataCache(ProceduralStream<Double> stream, int x, int z) {
        this(stream, x, z, true);
    }

    @BlockCoordinates
    public ChunkedDoubleDataCache(ProceduralStream<Double> stream, int x, int z, boolean cache) {
        this.x = x;
        this.z = z;
        this.stream = stream;
        this.cache = cache;
        this.data = new double[cache ? 256 : 0];
        if (cache) {
            Arrays.fill(this.data, Double.NaN);
        }
    }

    public void fill() {
        fill(null);
    }

    public void fill(Executor executor) {
        if (!cache) {
            return;
        }

        if (stream instanceof ChunkFillableDoubleStream2D cachedStream) {
            cachedStream.fillChunkDoubles(x, z, data);
            return;
        }

        for (int row = 0; row < 16; row++) {
            int rowOffset = row << 4;
            int worldZ = z + row;
            for (int column = 0; column < 16; column++) {
                data[rowOffset + column] = stream.getDouble(x + column, worldZ);
            }
        }
    }

    @BlockCoordinates
    public double getDouble(int x, int z) {
        if (!cache) {
            return stream.getDouble(this.x + x, this.z + z);
        }

        int index = (z << 4) + x;
        double value = data[index];
        if (!Double.isNaN(value)) {
            return value;
        }

        double sampled = stream.getDouble(this.x + x, this.z + z);
        data[index] = sampled;
        return sampled;
    }
}
