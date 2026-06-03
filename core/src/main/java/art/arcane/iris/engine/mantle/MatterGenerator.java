package art.arcane.iris.engine.mantle;

import art.arcane.iris.core.nms.container.Pair;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.documentation.ChunkCoordinates;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface MatterGenerator {
    Executor DISPATCHER = MultiBurst.burst;

    Engine getEngine();

    Mantle<Matter> getMantle();

    int getRadius();

    int getRealRadius();

    List<Pair<List<MantleComponent>, Integer>> getComponents();

    @ChunkCoordinates
    default void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        if (!getEngine().getDimension().isUseMantle()) {
            return;
        }

        int writeRadius = getRadius();
        Set<Long> partialChunks = new HashSet<>();

        try (MantleWriter writer = new MantleWriter(getEngine().getMantle(), getMantle(), x, z, writeRadius, multicore)) {
            for (Pair<List<MantleComponent>, Integer> pair : getComponents()) {
                int passRadius = pair.getB();
                List<CompletableFuture<Void>> launchedTasks = multicore ? new ArrayList<>() : null;

                for (int i = -passRadius; i <= passRadius; i++) {
                    for (int j = -passRadius; j <= passRadius; j++) {
                        int passX = x + i;
                        int passZ = z + j;
                        long passKey = chunkKey(passX, passZ);

                        MantleChunk<Matter> chunk = writer.acquireChunk(passX, passZ);
                        if (chunk.isFlagged(MantleFlag.PLANNED)) {
                            continue;
                        }

                        for (MantleComponent component : pair.getA()) {
                            if (!component.isEnabled()) {
                                continue;
                            }

                            if (chunk.isFlagged(component.getFlag())) {
                                continue;
                            }

                            int componentRadius = component.getRadius();
                            if (componentRadius > 0) {
                                int componentPassRadius = Math.ceilDiv(componentRadius, 16);
                                if (Math.abs(i) > componentPassRadius || Math.abs(j) > componentPassRadius) {
                                    partialChunks.add(passKey);
                                    continue;
                                }
                            }

                            MantleFlag[] prerequisites = component.getPrerequisiteFlags();
                            if (prerequisites.length > 0) {
                                boolean prerequisitesMet = true;
                                for (MantleFlag prereq : prerequisites) {
                                    if (!chunk.isFlagged(prereq)) {
                                        prerequisitesMet = false;
                                        break;
                                    }
                                }
                                if (!prerequisitesMet) {
                                    partialChunks.add(passKey);
                                    continue;
                                }
                            }

                            int finalPassX = passX;
                            int finalPassZ = passZ;
                            MantleChunk<Matter> finalChunk = chunk;
                            MantleComponent finalComponent = component;
                            Runnable task = () -> finalChunk.raiseFlagUnchecked(finalComponent.getFlag(),
                                    () -> finalComponent.generateLayer(writer, finalPassX, finalPassZ, context));

                            if (multicore) {
                                launchedTasks.add(CompletableFuture.runAsync(task, DISPATCHER));
                            } else {
                                task.run();
                            }
                        }
                    }
                }

                if (multicore) {
                    for (CompletableFuture<Void> launchedTask : launchedTasks) {
                        launchedTask.join();
                    }
                }
            }

            for (int i = -getRealRadius(); i <= getRealRadius(); i++) {
                for (int j = -getRealRadius(); j <= getRealRadius(); j++) {
                    int realX = x + i;
                    int realZ = z + j;
                    long realKey = chunkKey(realX, realZ);
                    if (partialChunks.contains(realKey)) {
                        continue;
                    }
                    writer.acquireChunk(realX, realZ).flag(MantleFlag.PLANNED, true);
                }
            }
        }
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
