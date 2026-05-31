package art.arcane.iris.core.nms.v26_1_R1;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import art.arcane.iris.Iris;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisVanillaStructureControl;
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
import art.arcane.iris.engine.framework.StructurePlacementGrid;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.StructurePlacementRoute;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.data.cache.Cache;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
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

    public IrisChunkGenerator(ChunkGenerator delegate, long seed, Engine engine, World world) {
        super(((CraftWorld) world).getHandle(), edit(delegate, new CustomBiomeSource(seed, engine, world)), null);
        this.delegate = delegate;
        this.engine = engine;
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> holders, BlockPos pos, int radius, boolean findUnexplored) {
        if (!vanillaControl().active()) {
            return null;
        }
        return delegate.findNearestMapStructure(level, holders, pos, radius, findUnexplored);
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
        if (!vanillaControl().active()) {
            return;
        }
        delegate.createStructures(registryAccess, structureState, structureManager, access, templateManager, levelKey);
    }

    private IrisVanillaStructureControl vanillaControl() {
        return engine.getDimension().getVanillaStructures();
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
        return delegate.createBiomes(randomstate, blender, structuremanager, ichunkaccess);
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
        if (vanillaControl().active()) {
            placeVanillaStructures(generatoraccessseed, ichunkaccess, structuremanager);
        }
        placeIrisNativeStructures(generatoraccessseed, ichunkaccess);
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
        IrisVanillaStructureControl control = vanillaControl();
        int undergroundShift = control.getUndergroundYShift();
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.getOrDefault(step, List.of())) {
                Object id = registry.getKey(structure);
                if (control.shouldGenerate(id == null ? null : id.toString())) {
                    random.setFeatureSeed(decoSeed, index, step);
                    int yShift = (undergroundShift != 0 && isUndergroundStep(structure.step())) ? undergroundShift : 0;
                    WorldGenLevel target = yShift == 0 ? world : yShiftedLevel(world, yShift);
                    try {
                        structureManager.startsForStructure(sectionPos, structure)
                                .forEach(start -> start.placeInChunk(target, structureManager, this, random, area, chunkPos));
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

    private WorldGenLevel yShiftedLevel(WorldGenLevel world, int yShift) {
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof BlockPos bp) {
                                args[i] = new BlockPos(bp.getX(), bp.getY() + yShift, bp.getZ());
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

    private void placeIrisNativeStructures(WorldGenLevel world, ChunkAccess chunk) {
        if (!(world instanceof WorldGenRegion region)) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        int cx = chunkPos.getMinBlockX() >> 4;
        int cz = chunkPos.getMinBlockZ() >> 4;
        int bx = 8 + (cx << 4);
        int bz = 8 + (cz << 4);
        IrisBiome biome = engine.getComplex().getTrueBiomeStream().get(bx, bz);
        IrisRegion ireg = engine.getComplex().getRegionStream().get(bx, bz);
        KList<IrisStructurePlacement> placements = new KList<>();
        if (biome != null) {
            placements.addAll(biome.getStructures());
        }
        if (ireg != null) {
            placements.addAll(ireg.getStructures());
        }
        placements.addAll(engine.getDimension().getStructures());
        if (placements.isEmpty()) {
            return;
        }

        long seed = world.getSeed();
        RNG rng = new RNG(Cache.key(cx, cz) + seed);
        ServerLevel level = region.getLevel();
        Registry<Structure> registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        RandomState randomState = level.getChunkSource().randomState();
        StructureTemplateManager templateManager = level.getStructureManager();
        StructureManager structureManager = level.structureManager();
        BiomeSource biomeSource = getBiomeSource();
        ResourceKey<Level> levelKey = level.dimension();
        int minY = getMinY() + 1;
        int maxY = getMinY() + engine.getHeight() - 1;

        for (IrisStructurePlacement placement : placements) {
            if (placement.getRoute() != StructurePlacementRoute.NATIVE_AT_POINT || placement.getVanilla().isEmpty()) {
                continue;
            }
            if (!StructurePlacementGrid.startsInChunk(placement, cx, cz, seed, rng)) {
                continue;
            }
            String key = placement.getVanilla().get(rng.i(0, placement.getVanilla().size() - 1)).toLowerCase();
            try {
                String[] parts = key.split(":", 2);
                Identifier id = parts.length > 1 ? Identifier.fromNamespaceAndPath(parts[0], parts[1]) : Identifier.fromNamespaceAndPath("minecraft", parts[0]);
                Holder<Structure> holder = registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE, id));
                Structure structure = holder.value();
                StructureStart start = structure.generate(holder, levelKey, world.registryAccess(), this, biomeSource, randomState, templateManager, seed, chunkPos, 0, level, b -> true);
                if (start == null || !start.isValid()) {
                    continue;
                }
                BoundingBox box = start.getBoundingBox();
                WorldgenRandom placeRandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
                for (int scx = box.minX() >> 4; scx <= box.maxX() >> 4; scx++) {
                    for (int scz = box.minZ() >> 4; scz <= box.maxZ() >> 4; scz++) {
                        ChunkPos target = new ChunkPos(scx, scz);
                        BoundingBox chunkBox = new BoundingBox(target.getMinBlockX(), minY, target.getMinBlockZ(), target.getMaxBlockX(), maxY, target.getMaxBlockZ());
                        start.placeInChunk(world, structureManager, this, placeRandom, chunkBox, target);
                    }
                }
            } catch (Throwable e) {
                Iris.reportError(e);
            }
        }
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
