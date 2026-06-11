package art.arcane.iris.core.nms.v26_1_R1;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import art.arcane.iris.Iris;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisImportedStructureControl;
import art.arcane.iris.util.common.reflect.WrappedField;
import art.arcane.iris.util.common.reflect.WrappedReturningMethod;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.core.registries.Registries;
import java.util.stream.Collectors;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.spigotmc.SpigotWorldConfig;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class IrisChunkGenerator extends CustomChunkGenerator {
    private static final WrappedField<ChunkGenerator, BiomeSource> BIOME_SOURCE;
    private static final WrappedReturningMethod<Heightmap, Object> SET_HEIGHT;
    private final ChunkGenerator delegate;
    private final Engine engine;
    private final CustomBiomeSource customBiomeSource;
    private volatile Set<String> reachableStructureKeysCache;

    public IrisChunkGenerator(ChunkGenerator delegate, long seed, Engine engine, World world) {
        this(delegate, engine, world, new CustomBiomeSource(seed, engine, world));
    }

    private IrisChunkGenerator(ChunkGenerator delegate, Engine engine, World world, CustomBiomeSource customBiomeSource) {
        super(((CraftWorld) world).getHandle(), edit(delegate, customBiomeSource), world.getGenerator());
        this.delegate = delegate;
        this.engine = engine;
        this.customBiomeSource = customBiomeSource;
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> holders, BlockPos pos, int radius, boolean findUnexplored) {
        try {
            Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            BlockPos best = null;
            Holder<Structure> bestHolder = null;
            long bestDist = Long.MAX_VALUE;
            for (Holder<Structure> holder : holders) {
                Object id = registry.getKey(holder.value());
                if (id == null) {
                    continue;
                }
                int[] at = IrisStructureLocator.locate(engine, id.toString(), pos.getX(), pos.getZ(), Math.max(1, radius));
                if (at == null) {
                    continue;
                }
                long dx = (long) at[0] - pos.getX();
                long dz = (long) at[2] - pos.getZ();
                long d = dx * dx + dz * dz;
                if (d < bestDist) {
                    bestDist = d;
                    best = new BlockPos(at[0], at[1], at[2]);
                    bestHolder = holder;
                }
            }
            if (best != null) {
                return Pair.of(best, bestHolder);
            }
        } catch (Throwable e) {
            Iris.reportError(e);
        }
        if (!importedControl().active()) {
            return null;
        }
        HolderSet<Structure> reachable = filterReachableStructures(level, holders);
        if (reachable == null || reachable.size() == 0) {
            return null;
        }
        try {
            return delegate.findNearestMapStructure(level, reachable, pos, radius, findUnexplored);
        } catch (Throwable e) {
            Iris.error("Vanilla structure locate failed near " + pos.getX() + ", " + pos.getZ() + ": " + e);
            Iris.reportError(e);
            return null;
        }
    }

    private HolderSet<Structure> filterReachableStructures(ServerLevel level, HolderSet<Structure> holders) {
        Set<String> reachable = reachableStructureKeysCache;
        if (reachable == null) {
            reachable = VanillaStructureBiomes.reachableStructureKeys(level, delegate.getBiomeSource());
            reachableStructureKeysCache = reachable;
        }
        if (reachable.isEmpty()) {
            return holders;
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> kept = new ArrayList<>();
        for (Holder<Structure> holder : holders) {
            Object id = registry.getKey(holder.value());
            if (id != null && reachable.contains(id.toString())) {
                kept.add(holder);
            }
        }
        if (kept.size() == holders.size()) {
            return holders;
        }
        return HolderSet.direct(kept);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.unit(null);
    }

    @Override
    public ChunkGenerator getDelegate() {
        if (delegate instanceof CustomChunkGenerator chunkGenerator)
            return chunkGenerator.getDelegate();
        return delegate;
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

    @Override
    public int getSeaLevel() {
        return delegate.getSeaLevel();
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structureManager, ChunkAccess access, StructureTemplateManager templateManager, ResourceKey<Level> levelKey) {
        if (!importedControl().active()) {
            return;
        }
        super.createStructures(registryAccess, structureState, structureManager, access, templateManager, levelKey);
    }

    private IrisImportedStructureControl importedControl() {
        return engine.getDimension().getImportedStructures();
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> holderlookup, RandomState randomstate, long i, SpigotWorldConfig conf) {
        return delegate.createState(holderlookup, randomstate, i, conf);
    }

    @Override
    public void createReferences(WorldGenLevel generatoraccessseed, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        delegate.createReferences(generatoraccessseed, structuremanager, ichunkaccess);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomstate, Blender blender, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        ichunkaccess.fillBiomesFromNoise(customBiomeSource::getVisibleNoiseBiome, randomstate.sampler());
        return CompletableFuture.completedFuture(ichunkaccess);
    }

    @Override
    public void buildSurface(WorldGenRegion regionlimitedworldaccess, StructureManager structuremanager, RandomState randomstate, ChunkAccess ichunkaccess) {
        delegate.buildSurface(regionlimitedworldaccess, structuremanager, randomstate, ichunkaccess);
    }

    @Override
    public void applyCarvers(WorldGenRegion regionlimitedworldaccess, long seed, RandomState randomstate, BiomeManager biomemanager, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        delegate.applyCarvers(regionlimitedworldaccess, seed, randomstate, biomemanager, structuremanager, ichunkaccess);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomstate, StructureManager structuremanager, ChunkAccess ichunkaccess) {
        return delegate.fillFromNoise(blender, randomstate, structuremanager, ichunkaccess);
    }

    @Override
    public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> holder, StructureManager structuremanager, MobCategory enumcreaturetype, BlockPos blockposition) {
        return delegate.getMobsAt(holder, structuremanager, enumcreaturetype, blockposition);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager) {
        applyBiomeDecoration(generatoraccessseed, ichunkaccess, structuremanager, true);
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState randomstate, BlockPos blockposition) {
        delegate.addDebugScreenInfo(list, randomstate, blockposition);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager, boolean vanilla) {
        addVanillaDecorations(generatoraccessseed, ichunkaccess, structuremanager);
        if (importedControl().active()) {
            placeVanillaStructures(generatoraccessseed, ichunkaccess, structuremanager);
        }
        delegate.applyBiomeDecoration(generatoraccessseed, ichunkaccess, structuremanager, false);
    }

    private void placeVanillaStructures(WorldGenLevel world, ChunkAccess chunk, StructureManager structureManager) {
        if (!structureManager.shouldGenerateStructures()) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, world.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Map<Integer, List<Structure>> byStep = registry.stream().collect(Collectors.groupingBy(s -> s.step().ordinal()));
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decoSeed = random.setDecorationSeed(world.getSeed(), origin.getX(), origin.getZ());
        BoundingBox area = writableArea(chunk);
        int steps = GenerationStep.Decoration.values().length;
        IrisImportedStructureControl control = importedControl();
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.getOrDefault(step, List.of())) {
                Object id = registry.getKey(structure);
                String structureId = id == null ? null : id.toString();
                if (control.shouldGenerate(structureId) && !IrisStructureLocator.suppressesVanilla(engine, structureId)) {
                    random.setFeatureSeed(decoSeed, index, step);
                    int[] offset = control.resolveOffset(structureId, isUndergroundStep(structure.step()));
                    boolean shifted = offset[0] != 0 || offset[1] != 0 || offset[2] != 0;
                    WorldGenLevel target = shifted ? shiftedLevel(world, offset[0], offset[1], offset[2]) : world;
                    BoundingBox placeArea = shifted
                            ? new BoundingBox(area.minX() - offset[0], area.minY(), area.minZ() - offset[2], area.maxX() - offset[0], area.maxY(), area.maxZ() - offset[2])
                            : area;
                    try {
                        structureManager.startsForStructure(sectionPos, structure)
                                .forEach(start -> start.placeInChunk(target, structureManager, this, random, placeArea, chunkPos));
                    } catch (Throwable e) {
                        Iris.reportError(e);
                    }
                }
                index++;
            }
        }
    }

    private static boolean isUndergroundStep(GenerationStep.Decoration step) {
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.STRONGHOLDS;
    }

    private WorldGenLevel shiftedLevel(WorldGenLevel world, int dx, int dy, int dz) {
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof BlockPos bp) {
                                args[i] = new BlockPos(bp.getX() + dx, bp.getY() + dy, bp.getZ() + dz);
                            }
                        }
                    }
                    try {
                        return method.invoke(world, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int i = cp.getMinBlockX();
        int j = cp.getMinBlockZ();
        int minY = getMinY() + 1;
        int maxY = getMinY() + engine.getHeight() - 1;
        return new BoundingBox(i, minY, j, i + 15, maxY, j + 15);
    }

    @Override
    public void addVanillaDecorations(WorldGenLevel level, ChunkAccess chunkAccess, StructureManager structureManager) {
        SectionPos sectionPos = SectionPos.of(chunkAccess.getPos(), level.getMinSectionY());
        BlockPos blockPos = sectionPos.origin();

        Heightmap surface = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap ocean = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap motion = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionNoLeaves = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wX = x + blockPos.getX();
                int wZ = z + blockPos.getZ();

                int terrainTop = engine.getHeight(wX, wZ, false) + engine.getMinHeight() + 1;
                int terrainNoFluid = engine.getHeight(wX, wZ, true) + engine.getMinHeight() + 1;
                SET_HEIGHT.invoke(ocean, x, z, terrainNoFluid);
                SET_HEIGHT.invoke(surface, x, z, terrainTop);
                SET_HEIGHT.invoke(motion, x, z, terrainTop);
                SET_HEIGHT.invoke(motionNoLeaves, x, z, terrainTop);
            }
        }

        Heightmap.primeHeightmaps(chunkAccess, ChunkStatus.FINAL_HEIGHTMAPS);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion regionlimitedworldaccess) {
        delegate.spawnOriginalMobs(regionlimitedworldaccess);
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor levelheightaccessor) {
        return delegate.getSpawnHeight(levelheightaccessor);
    }

    @Override
    public int getGenDepth() {
        return delegate.getGenDepth();
    }

    @Override
    public int getBaseHeight(int i, int j, Heightmap.Types heightmap_type, LevelHeightAccessor levelheightaccessor, RandomState randomstate) {
        return levelheightaccessor.getMinY() + engine.getHeight(i, j, !heightmap_type.isOpaque().test(Blocks.WATER.defaultBlockState())) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int i, int j, LevelHeightAccessor levelheightaccessor, RandomState randomstate) {
        int block = engine.getHeight(i, j, true);
        int water = engine.getHeight(i, j, false);
        BlockState[] column = new BlockState[levelheightaccessor.getHeight()];
        for (int k = 0; k < column.length; k++) {
            if (k <= block) column[k] = Blocks.STONE.defaultBlockState();
            else if (k <= water) column[k] = Blocks.WATER.defaultBlockState();
            else column[k] = Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(levelheightaccessor.getMinY(), column);
    }

    @Override
    public Optional<Identifier> getTypeNameForDataFixer() {
        return delegate.getTypeNameForDataFixer();
    }

    @Override
    public void validate() {
        delegate.validate();
    }

    static {
        Field biomeSource = null;
        for (Field field : ChunkGenerator.class.getDeclaredFields()) {
            if (!field.getType().equals(BiomeSource.class))
                continue;
            biomeSource = field;
            break;
        }
        if (biomeSource == null)
            throw new RuntimeException("Could not find biomeSource field in ChunkGenerator!");

        Method setHeight = null;
        for (Method method : Heightmap.class.getDeclaredMethods()) {
            var types = method.getParameterTypes();
            if (types.length != 3 || !Arrays.equals(types, new Class<?>[]{int.class, int.class, int.class})
                    || !method.getReturnType().equals(void.class))
                continue;
            setHeight = method;
            break;
        }
        if (setHeight == null)
            throw new RuntimeException("Could not find setHeight method in Heightmap!");

        BIOME_SOURCE = new WrappedField<>(ChunkGenerator.class, biomeSource.getName());
        SET_HEIGHT = new WrappedReturningMethod<>(Heightmap.class, setHeight.getName(), setHeight.getParameterTypes());
    }

    private static ChunkGenerator edit(ChunkGenerator generator, BiomeSource source) {
        try {
            BIOME_SOURCE.set(generator, source);
            if (generator instanceof CustomChunkGenerator custom)
                BIOME_SOURCE.set(custom.getDelegate(), source);

            return generator;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
