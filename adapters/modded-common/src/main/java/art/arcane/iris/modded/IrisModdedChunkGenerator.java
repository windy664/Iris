/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.hunk.Hunk;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IrisModdedChunkGenerator extends ChunkGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    public static final MapCodec<IrisModdedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec((RecordCodecBuilder.Instance<IrisModdedChunkGenerator> instance) -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter((IrisModdedChunkGenerator generator) -> generator.biomeSource),
            Codec.STRING.fieldOf("dimension").forGetter((IrisModdedChunkGenerator generator) -> generator.dimensionKey)
    ).apply(instance, IrisModdedChunkGenerator::new));

    private final String dimensionKey;
    private final ConcurrentHashMap<String, Holder<Biome>> biomeHolders = new ConcurrentHashMap<>();
    private final AtomicBoolean announced = new AtomicBoolean(false);
    private volatile Engine engine;
    private volatile String activePackKey;
    private volatile long seedOverride = Long.MIN_VALUE;

    public IrisModdedChunkGenerator(BiomeSource biomeSource, String dimensionKey) {
        super(biomeSource);
        this.dimensionKey = dimensionKey;
        this.activePackKey = dimensionKey;
    }

    public synchronized void repoint(String packKey, long seed) {
        ServerLevel level = boundLevel();
        if (level != null) {
            ModdedWorldEngines.evict(level);
        }
        this.activePackKey = packKey;
        this.seedOverride = seed;
        this.engine = null;
        this.announced.set(false);
        this.biomeHolders.clear();
    }

    public synchronized void unbindEngine() {
        ServerLevel level = boundLevel();
        if (level != null) {
            ModdedWorldEngines.evict(level);
        }
        this.engine = null;
        this.announced.set(false);
        this.biomeHolders.clear();
    }

    public synchronized void resetToDefault() {
        repoint(dimensionKey, Long.MIN_VALUE);
    }

    public String activePackKey() {
        return activePackKey;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private ServerLevel boundLevel() {
        MinecraftServer server = ModdedEngineBootstrap.currentServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() == this) {
                return level;
            }
        }
        return null;
    }

    private Engine engine() {
        Engine cached = engine;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (engine != null) {
                return engine;
            }
            ServerLevel level = boundLevel();
            if (level == null) {
                throw new IllegalStateException("Iris generator '" + dimensionKey + "' has no bound ServerLevel yet");
            }
            Engine created = ModdedWorldEngines.get(level, activePackKey, seedOverride);
            engine = created;
            return created;
        }
    }

    private Engine engineOrNull() {
        Engine cached = engine;
        if (cached != null) {
            return cached;
        }
        try {
            return engine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String dimensionKey() {
        return dimensionKey;
    }

    public Engine engineIfBound() {
        return engine;
    }

    public Engine commandEngine() {
        return engine();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        Engine generationEngine = engine();
        ChunkPos pos = chunk.getPos();
        if (announced.compareAndSet(false, true)) {
            LOGGER.info("Iris generating {} through IrisModdedChunkGenerator (dim={} first chunk {},{})",
                    dimensionKey, generationEngine.getDimension().getLoadKey(), pos.x(), pos.z());
        }
        LOGGER.debug("Iris generating chunk {},{}", pos.x(), pos.z());

        int dimMinY = generationEngine.getMinHeight();
        int dimMaxY = generationEngine.getMaxHeight();
        int height = dimMaxY - dimMinY;
        PlatformBlockState air = IrisPlatforms.get().registries().air();
        ModdedBlockBuffer blocks = new ModdedBlockBuffer(height, air);
        Hunk<PlatformBiome> biomes = Hunk.newArrayHunk(16, height, 16);
        try {
            generationEngine.generate(pos.getMinBlockX(), pos.getMinBlockZ(), blocks, biomes, false);
        } catch (Throwable e) {
            LOGGER.error("Iris failed to generate chunk {},{}", pos.x(), pos.z(), e);
            throw new IllegalStateException("Iris generation failed for chunk " + pos.x() + "," + pos.z(), e);
        }

        writeBlocks(chunk, blocks, dimMinY, height);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        Registry<Biome> biomeRegistry = structureManager.registryAccess().lookupOrThrow(Registries.BIOME);
        chunk.fillBiomesFromNoise(new HunkBiomeResolver(this, biomes, biomeRegistry, pos, dimMinY, height), randomState.sampler());
        return CompletableFuture.completedFuture(chunk);
    }

    private Holder<Biome> fallbackBiome(Registry<Biome> registry) {
        Holder<Biome> existing = biomeHolders.get("minecraft:plains");
        if (existing != null) {
            return existing;
        }
        Holder<Biome> resolved = registry.get(Identifier.fromNamespaceAndPath("minecraft", "plains"))
                .<Holder<Biome>>map((Holder.Reference<Biome> reference) -> reference)
                .orElseThrow(() -> new IllegalStateException("minecraft:plains missing from biome registry"));
        Holder<Biome> raced = biomeHolders.putIfAbsent("minecraft:plains", resolved);
        return raced != null ? raced : resolved;
    }

    private Holder<Biome> holderFor(Registry<Biome> registry, PlatformBiome biome) {
        if (biome == null) {
            return fallbackBiome(registry);
        }
        String key = biome.key();
        Holder<Biome> existing = biomeHolders.get(key);
        if (existing != null) {
            return existing;
        }
        Identifier identifier = Identifier.tryParse(key);
        Optional<Holder.Reference<Biome>> reference = identifier == null ? Optional.empty() : registry.get(identifier);
        Holder<Biome> resolved = reference.<Holder<Biome>>map((Holder.Reference<Biome> value) -> value).orElseGet(() -> fallbackBiome(registry));
        Holder<Biome> raced = biomeHolders.putIfAbsent(key, resolved);
        return raced != null ? raced : resolved;
    }

    public BiomeResolver regenBiomeResolver(Registry<Biome> registry, Hunk<PlatformBiome> biomes, ChunkPos pos) {
        Engine current = engine();
        int dimMinY = current.getMinHeight();
        int height = current.getMaxHeight() - dimMinY;
        return new HunkBiomeResolver(this, biomes, registry, pos, dimMinY, height);
    }

    private void writeBlocks(ChunkAccess chunk, ModdedBlockBuffer blocks, int dimMinY, int height) {
        int chunkMinY = chunk.getMinY();
        int chunkMaxY = chunkMinY + chunk.getHeight();
        int from = Math.max(dimMinY, chunkMinY);
        int to = Math.min(dimMinY + height, chunkMaxY);

        for (int y = from; y < to; ) {
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int sectionMinY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            int sectionEnd = Math.min(sectionMinY + 16, to);
            section.acquire();
            try {
                for (int blockY = y; blockY < sectionEnd; blockY++) {
                    int bufferY = blockY - dimMinY;
                    int localY = blockY & 15;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (blocks.isAir(x, bufferY, z)) {
                                continue;
                            }
                            PlatformBlockState state = blocks.getRaw(x, bufferY, z);
                            section.setBlockState(x, localY, z, (BlockState) state.nativeHandle(), false);
                        }
                    }
                }
            } finally {
                section.release();
            }
            y = sectionEnd;
        }
    }

    private static final class HunkBiomeResolver implements BiomeResolver {
        private final IrisModdedChunkGenerator generator;
        private final Hunk<PlatformBiome> biomes;
        private final Registry<Biome> registry;
        private final ChunkPos pos;
        private final int dimMinY;
        private final int height;

        private HunkBiomeResolver(IrisModdedChunkGenerator generator, Hunk<PlatformBiome> biomes, Registry<Biome> registry, ChunkPos pos, int dimMinY, int height) {
            this.generator = generator;
            this.biomes = biomes;
            this.registry = registry;
            this.pos = pos;
            this.dimMinY = dimMinY;
            this.height = height;
        }

        @Override
        public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
            int localX = QuartPos.toBlock(quartX) - pos.getMinBlockX();
            int localZ = QuartPos.toBlock(quartZ) - pos.getMinBlockZ();
            int bufferY = Math.max(0, Math.min(height - 1, QuartPos.toBlock(quartY) - dimMinY));
            PlatformBiome biome = biomes.get(localX, bufferY, localZ);
            return generator.holderFor(registry, biome);
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templateManager, ResourceKey<Level> levelKey) {
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        Engine current = engineOrNull();
        return current == null ? 384 : current.getMaxHeight() - current.getMinHeight();
    }

    @Override
    public int getSeaLevel() {
        Engine current = engineOrNull();
        return current == null ? 63 : current.getDimension().getFluidHeight();
    }

    @Override
    public int getMinY() {
        Engine current = engineOrNull();
        return current == null ? -64 : current.getMinHeight();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        Engine current = engineOrNull();
        if (current == null) {
            return heightAccessor.getMinY() + Math.min(heightAccessor.getHeight(), 64);
        }
        boolean ignoreFluid = type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG;
        return current.getMinHeight() + current.getHeight(x, z, ignoreFluid) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        int minY = heightAccessor.getMinY();
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        Engine current = engineOrNull();
        BlockState airState = Blocks.AIR.defaultBlockState();
        if (current == null) {
            for (int i = 0; i < states.length; i++) {
                states[i] = airState;
            }
            return new NoiseColumn(minY, states);
        }
        int surface = current.getMinHeight() + current.getHeight(x, z, true);
        int fluid = current.getDimension().getFluidHeight();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int i = 0; i < states.length; i++) {
            int y = minY + i;
            if (y <= surface) {
                states[i] = stone;
            } else if (y <= fluid) {
                states[i] = water;
            } else {
                states[i] = airState;
            }
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Iris dimension: " + dimensionKey);
    }
}
