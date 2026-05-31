package dev.lrxh.blockChanger;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Lifecycle;
import dev.lrxh.blockChanger.lighting.LightingService;
import dev.lrxh.blockChanger.snapshot.ChunkListener;
import dev.lrxh.blockChanger.snapshot.ChunkSectionKey;
import dev.lrxh.blockChanger.snapshot.ChunkSectionSnapshot;
import dev.lrxh.blockChanger.snapshot.CuboidSnapshot;
import dev.lrxh.blockChanger.util.GroupBuffer;
import dev.lrxh.blockChanger.world.VirtualLevelStorageSource;
import dev.lrxh.blockChanger.world.VirtualWorld;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Difficulty;

import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.apache.logging.log4j.util.InternalApi;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class BlockChanger {
    public static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final int[][] SHIFT_CACHE = new int[16][];
    private static final long[][] MASK_CACHE = new long[16][];
    private static final PalettedContainerFactory palettedContainerFactory = PalettedContainerFactory.create(MinecraftServer.getServer().registryAccess());
    private static final PalettedContainer<BlockState> states = palettedContainerFactory.createForBlockStates();
    private static JavaPlugin plugin;
    private static Field worldsField;
    private static Set<VirtualWorld> loadedWorlds;

    public static void initialize(final JavaPlugin plugin) {
        LightingService.setPlugin(plugin);
        plugin.getServer().getPluginManager().registerEvents(new ChunkListener(plugin), plugin);

        for (int bits = 1; bits <= 16; ++bits) {
            final int vp = 64 / bits;
            final int[] shifts = new int[vp];
            final long[] masks = new long[vp];
            final long mask = (1L << bits) - 1L;
            for (int p = 0; p < vp; ++p) {
                final int s = p * bits;
                shifts[p] = s;
                masks[p] = mask << s;
            }
            SHIFT_CACHE[bits - 1] = shifts;
            MASK_CACHE[bits - 1] = masks;
        }
        BlockChanger.plugin = plugin;
        VirtualLevelStorageSource.warmup();
        try {
            worldsField = CraftServer.class.getDeclaredField("worlds");
            worldsField.setAccessible(true);
        } catch (Exception ignored) {
        }

        loadedWorlds = ConcurrentHashMap.newKeySet();

        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onServerShutdown(PluginDisableEvent event) {
                if (event.getPlugin().getName().equals(plugin.getName())) {
                    for (VirtualWorld world : loadedWorlds) {
                        world.unload();
                    }
                }
            }
        }, plugin);
    }

    /**
     * Create a snapshot of a whole chunk using NBT serialization.
     * <p>
     * This uses {@link SerializableChunkData#copyOf} to deep-copy the entire chunk state
     * (sections, block entities, heightmaps) into an NBT tag. The returned snapshot is safe
     * to store and later restore without being affected by further chunk changes.
     * <p>
     * <b>Must be called on the main thread.</b>
     *
     * @param chunk the Bukkit chunk to snapshot
     * @return a {@link ChunkSectionSnapshot} containing the serialized chunk NBT and position
     */
    @InternalApi
    public static ChunkSectionSnapshot createChunkBlockSnapshot(final Chunk chunk, final int minY, final int maxY) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("createChunkBlockSnapshot must be called on the main thread");
        }

        final CraftChunk craftChunk = (CraftChunk) chunk;
        final ServerLevel level = craftChunk.getCraftWorld().getHandle();
        final LevelChunk levelChunk = level.getChunk(chunk.getX(), chunk.getZ());
        final ChunkPos position = levelChunk.getPos();

        final SerializableChunkData data = SerializableChunkData.copyOf(level, levelChunk);
        final CompoundTag tag = data.write();

        return new ChunkSectionSnapshot(tag, position);
    }

    /**
     * Restore a chunk from an NBT snapshot.
     * <p>
     * All NMS mutation is performed on the server main thread for thread-safety.
     * Non-player entities are optionally removed before the restore.
     * The chunk is properly re-synced to nearby players after restore.
     *
     * @param chunk         the Bukkit chunk to restore
     * @param snapshot      NBT snapshot to restore from
     * @param clearEntities true to clear non-player entities
     * @return a CompletableFuture that completes when the restore finishes
     */
    @InternalApi
    public static CompletableFuture<Void> restoreChunkBlockSnapshot(final Chunk chunk, final ChunkSectionSnapshot snapshot,
                                                                    final boolean clearEntities) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().getMainThreadExecutor(plugin).execute(() -> {
            try {
                _restoreChunkBlockSnapshot(chunk, snapshot, clearEntities);
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to restore chunk snapshot", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Internal restore implementation. Must run on the main thread.
     */
    private static void _restoreChunkBlockSnapshot(final Chunk chunk, final ChunkSectionSnapshot snapshot, final boolean clearEntities) {
        final CraftChunk craftChunk = (CraftChunk) chunk;
        final ServerLevel level = craftChunk.getCraftWorld().getHandle();
        final ChunkPos position = snapshot.position();

        final CompoundTag tag = snapshot.nbt();
        final SerializableChunkData scd = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
        if (scd == null) {
            log("Parsed SerializableChunkData is null for chunk " + position.x + "," + position.z);
            return;
        }

        final RegionStorageInfo storageInfo = new RegionStorageInfo(
                level.getWorld().getName(), level.dimension(), "chunk");
        final ProtoChunk proto = scd.read(level, level.getPoiManager(), storageInfo, position);

        if (!(proto instanceof ImposterProtoChunk imposter)) {
            log("Chunk " + position.x + "," + position.z + " not a LevelChunk — skipping");
            return;
        }

        final LevelChunk liveChunk = (LevelChunk) ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);

        if (clearEntities) {
            clearEntitiesInChunk(chunk);
        }

        swapSections(liveChunk, imposter.getWrapped());

        // Re-sync chunk to players
        level.getChunkSource().chunkMap
                .getPlayers(position, false)
                .forEach(sp -> sp.connection.send(
                        new ClientboundLevelChunkWithLightPacket(
                                liveChunk, level.getLightEngine(), null, null)));
    }

    private static void clearEntitiesInChunk(final Chunk chunk) {
        final org.bukkit.World world = chunk.getWorld();
        if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) return;

        for (final org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity.getType() == org.bukkit.entity.EntityType.PLAYER) continue;
            entity.remove();
        }
    }

    private static void swapSections(final LevelChunk dst, final LevelChunk src) {
        final LevelChunkSection[] srcSec = src.getSections();
        final LevelChunkSection[] dstSec = dst.getSections();
        for (int i = 0; i < srcSec.length && i < dstSec.length; i++) {
            dstSec[i] = srcSec[i];
        }

        dst.getBlockEntities().clear();
        dst.getBlockEntities().putAll(src.getBlockEntities());

        Heightmap.primeHeightmaps(dst, EnumSet.of(
                Heightmap.Types.WORLD_SURFACE,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));

        dst.markUnsaved();
    }

    /**
     * Create an empty chunk section with default air blocks and default biomes.
     * <p>
     * This helper builds the state and biomes paletted containers used by LevelChunkSection.
     *
     * @param level level used to obtain biomes registry and default holder
     * @return a new empty {@link LevelChunkSection}
     */
    private static LevelChunkSection createEmptySection(final Level level) {
        final Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        final Holder<Biome> defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeRegistry.asHolderIdMap());

        final PalettedContainer<Holder<Biome>> biomes = new PalettedContainerFactory(
                null, null, null, biomeStrategy, defaultBiome, null, null
        ).createForBiomes();

        LevelChunkSection section = new LevelChunkSection(states, biomes);
        section.recalcBlockCounts();

        return section;
    }

    /**
     * Low level writer that writes palette ids into a section's raw BitStorage.
     * <p>
     * This method packs the provided {@code paletteIds} into the section's BitStorage
     * using cached shift/mask tables. It performs a parallel write by collecting
     * per-thread masks/values and combining them in a final pass to avoid contention.
     *
     * @param section    target section to modify
     * @param indices    indices inside the section (0..4096) where values should be written
     * @param paletteIds palette ids corresponding to each index
     */
    private static void writePaletteIds(LevelChunkSection section, int[] indices, int[] paletteIds) {
        final int n = paletteIds.length;
        if (n == 0) return;

        final PalettedContainer<BlockState> container = section.states;
        final PalettedContainer.Data<BlockState> data = container.data;
        final BitStorage storage = data.storage();
        final int bits = storage.getBits();
        if (bits == 0) {
            section.recalcBlockCounts();
            return;
        }

        final int valuesPerLong = 64 / bits;
        final int[] shifts = SHIFT_CACHE[bits - 1];
        final long[] masks = MASK_CACHE[bits - 1];
        final long[] raw = storage.getRaw();
        final long bitMask = (1L << bits) - 1L;
        final int rawLen = raw.length;

        final long[] batchMasks = new long[rawLen];
        final long[] batchValues = new long[rawLen];

        for (int i = 0; i < n; i++) {
            final int idx = indices[i];
            final int pid = paletteIds[i] & (int) bitMask;
            final int cell = idx / valuesPerLong;
            final int pos = idx % valuesPerLong;
            batchMasks[cell] |= masks[pos];
            batchValues[cell] |= ((long) pid) << shifts[pos];
        }

        for (int j = 0; j < rawLen; j++) {
            raw[j] = (raw[j] & ~batchMasks[j]) | batchValues[j];
        }

        section.recalcBlockCounts();
    }

    /**
     * Set positions inside a section to the given block states.
     * <p>
     * This method resolves palette ids for each state in parallel and then calls
     * {@link #writePaletteIds} to update the packed storage.
     *
     * @param section section to modify
     * @param indices indices inside the section (0..4095)
     * @param states  block states to write at the corresponding indices
     */
    private static void setAll(LevelChunkSection section, int[] indices, BlockState[] states) {
        final int n = states.length;
        if (n == 0) return;

        final PalettedContainer<BlockState> container = section.states;
        final PalettedContainer.Data<BlockState> data = container.data;
        final Palette<BlockState> palette = data.palette();

        final int[] paletteIds = new int[n];
        final HashMap<BlockState, Integer> paletteCache = new HashMap<>(Math.min(n, 16));
        synchronized (palette) {
            for (int i = 0; i < n; i++) {
                final BlockState state = states[i];
                Integer id = paletteCache.get(state);
                if (id == null) {
                    id = palette.idFor(state, PaletteResize.noResizeExpected());
                    paletteCache.put(state, id);
                }
                paletteIds[i] = id;
            }
        }

        writePaletteIds(section, indices, paletteIds);
    }


    /**
     * Asynchronously set a collection of block changes.
     * <p>
     * The map keys are {@link Location} objects and values are {@link BlockData}.
     * Changes are grouped per chunk, converted to native Minecraft {@link BlockState} and
     * written directly into chunk sections. Lighting is optionally updated after the changes.
     *
     * @param blocks         map of locations to block data to apply
     * @param updateLighting if true, run lighting updates for all affected chunks
     * @return a {@link CompletableFuture} that completes once the work and optional lighting updates begin
     */
    public static CompletableFuture<Void> setBlocks(final Map<Location, BlockData> blocks, final boolean updateLighting) {
        if (blocks == null || blocks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final ServerLevel level = ((CraftChunk) blocks.keySet().iterator().next().getChunk()).getCraftWorld().getHandle();
        final World bukkitWorld = blocks.keySet().iterator().next().getWorld();

        final ConcurrentMap<BlockData, net.minecraft.world.level.block.state.BlockState> stateCache =
                new ConcurrentHashMap<>(Math.max(16, blocks.size() >>> 2));

        final ConcurrentHashMap<ChunkSectionKey, GroupBuffer> groups =
                new ConcurrentHashMap<>(Math.max(16, blocks.size() >>> 4));

        blocks.entrySet().parallelStream().forEach(entry -> {
            final Location loc = entry.getKey();
            final BlockData bd = entry.getValue();

            final int chunkX = loc.getBlockX() >> 4;
            final int chunkZ = loc.getBlockZ() >> 4;
            final int sectionIndex = level.getSectionIndex(loc.getBlockY());

            final ChunkSectionKey key = new ChunkSectionKey(chunkX, chunkZ, sectionIndex);

            final net.minecraft.world.level.block.state.BlockState state =
                    stateCache.computeIfAbsent(bd, k -> ((CraftBlockData) k).getState());

            final int bx = loc.getBlockX();
            final int by = loc.getBlockY();
            final int bz = loc.getBlockZ();
            final int idx = ((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15);

            groups.computeIfAbsent(key, k -> new GroupBuffer(8)).append(idx, state);
        });

        final ConcurrentHashMap<ChunkPos, CompletableFuture<Chunk>> chunkCache = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> chunkFutures = groups.entrySet().stream()
                .map(entry -> {
                    final ChunkSectionKey key = entry.getKey();
                    final GroupBuffer gb = entry.getValue();

                    final int sectionIndex = key.sectionIndex();
                    final int chunkX = key.chunkX();
                    final int chunkZ = key.chunkZ();

                    final ChunkPos pos = new ChunkPos(chunkX, chunkZ);

                    CompletableFuture<Chunk> chunkFuture = chunkCache.computeIfAbsent(pos,
                            k -> bukkitWorld.getChunkAtAsync(chunkX, chunkZ, false));

                    return chunkFuture.thenAcceptAsync(chunk -> {
                        final ChunkAccess access = ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);
                        LevelChunkSection[] sections = access.getSections();
                        LevelChunkSection section = sections[sectionIndex];
                        if (section == null) {
                            section = createEmptySection(level);
                            sections[sectionIndex] = section;
                        }

                        final int n;
                        synchronized (gb) {
                            n = gb.size;
                        }
                        if (n == 0) return;

                        final int[] indices = new int[n];
                        final BlockState[] states = new BlockState[n];
                        synchronized (gb) {
                            System.arraycopy(gb.indices, 0, indices, 0, n);
                            System.arraycopy(gb.states, 0, states, 0, n);
                        }

                        setAll(section, indices, states);
                    });
                })
                .toList();

        return CompletableFuture
                .allOf(chunkCache.values().toArray(new CompletableFuture[0]))
                .thenApply(v -> chunkCache.values().stream()
                        .map(f -> f.getNow(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())
                )
                .thenAcceptAsync(changedChunks -> {
                    if (updateLighting && !groups.isEmpty()) {
                        LightingService.updateLighting(changedChunks, true);
                    } else {
                        for (Chunk chunk : changedChunks) {
                            bukkitWorld.refreshChunk(chunk.getX(), chunk.getZ());
                        }
                    }
                });
    }

    /**
     * Asynchronously creates an in-memory virtual world that does not persist to disk.
     * <p>
     * The world is configured using the provided {@link WorldCreator} and behaves
     * like a normal {@link org.bukkit.World}, including support for game rules,
     * world borders, and biome generation. However, it exists entirely in memory,
     * making it ideal for temporary scenarios such as minigame arenas.
     * <p>
     * The returned {@link CompletableFuture} completes once the world has been fully
     * initialized and loaded. World initialization is done asynchronously.
     * Note: Although the world functions like a standard {@link org.bukkit.World},
     * it will not be saved to disk, and changes are lost when the server shuts down.
     *
     * @param creator the configuration settings for the virtual world
     * @return a {@link CompletableFuture} that completes with the loaded {@link VirtualWorld}
     */
    @SuppressWarnings("UnstableApiUsage")
    public static CompletableFuture<VirtualWorld> createVirtualWorld(WorldCreator creator) {
        return CompletableFuture.supplyAsync(() -> {

            final MinecraftServer server = MinecraftServer.getServer();
            final CraftServer craftServer = (CraftServer) Bukkit.getServer();

            final WorldLoader.DataLoadContext context = craftServer.getServer().worldLoaderContext;

            final LevelStorageSource storageSource = LevelStorageSource.createDefault(craftServer.getWorldContainer().toPath().resolve(creator.name()));

            final ResourceKey<LevelStem> actualDimension = switch (creator.environment()) {
                case NORMAL -> LevelStem.OVERWORLD;
                case NETHER -> LevelStem.NETHER;
                case THE_END -> LevelStem.END;
                default -> null; // This can't be reached
            };

            LevelStorageSource.LevelStorageAccess
                    levelStorageAccess = VirtualLevelStorageSource.createVirtual(
                    storageSource,
                    creator.name(),
                    craftServer.getWorldContainer().toPath().resolve(creator.name()),
                    actualDimension
            );

            final RegistryAccess.Frozen registryAccess = context.datapackDimensions();
            @SuppressWarnings("OptionalGetWithoutIsPresent") final Registry<LevelStem> contextLevelStemRegistry = registryAccess.lookup(Registries.LEVEL_STEM).get();

            final WorldOptions worldOptions = new WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest());
            final DedicatedServerProperties.WorldDimensionData properties = new DedicatedServerProperties.WorldDimensionData(
                    GsonHelper.parse(creator.generatorSettings().isEmpty() ? "{}" : creator.generatorSettings()),
                    creator.type().name().toLowerCase(Locale.ROOT)
            );
            final LevelSettings levelSettings = new LevelSettings(
                    creator.name(),
                    GameType.byId(craftServer.getDefaultGameMode().getValue()),
                    creator.hardcore(),
                    Difficulty.EASY,
                    true,
                    new GameRules(context.dataConfiguration().enabledFeatures()),
                    context.dataConfiguration()
            );
            final WorldDimensions worldDimensions = properties.create(context.datapackWorldgen());
            final WorldDimensions.Complete complete = worldDimensions.bake(contextLevelStemRegistry);
            final Lifecycle lifecycle = complete.lifecycle().add(context.datapackWorldgen().allRegistriesLifecycle());
            final PrimaryLevelData primaryLevelData = new PrimaryLevelData(levelSettings, worldOptions, complete.specialWorldProperty(), lifecycle);
            final LevelStem levelStem = WorldPresets.createNormalWorldDimensions(context.datapackWorldgen()).dimensions().get(LevelStem.OVERWORLD);
            final ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(creator.key().namespace(), creator.key().value()));

            final ServerLevel serverLevel = new ServerLevel(
                    server,
                    EXECUTOR,
                    levelStorageAccess,
                    primaryLevelData,
                    dimensionKey,
                    levelStem,
                    primaryLevelData.isDebugWorld(),
                    BiomeManager.obfuscateSeed(primaryLevelData.worldGenOptions().seed()),
                    ImmutableList.of(),
                    true,
                    server.overworld().getRandomSequences(),
                    creator.environment(),
                    Objects.requireNonNull(creator.generator()),
                    Objects.requireNonNull(creator.biomeProvider())
            );

            craftServer.getServer().addLevel(serverLevel);
            serverLevel.setSpawnSettings(true);

            Bukkit.getScheduler().getMainThreadExecutor(plugin).execute(() -> {
                WorldBorder worldborder = serverLevel.getWorldBorder();
                Optional<WorldBorder.Settings> wbSettings = primaryLevelData.getLegacyWorldBorderSettings();
                wbSettings.ifPresent(worldborder::applySettings);
                new WorldLoadEvent(serverLevel.getWorld()).callEvent();
            });

            try {
                @SuppressWarnings("unchecked") Map<String, World> worlds = (Map<String, World>) worldsField.get(Bukkit.getServer());
                worlds.remove(serverLevel.getWorld().getName());
            } catch (Exception ignored) {

            }
            VirtualWorld v = new VirtualWorld(serverLevel);

            loadedWorlds.add(v);

            return v;
        }, EXECUTOR);
    }


    public static void paste(World world, CuboidSnapshot snapshot, boolean updateLighting) {
        for (Map.Entry<Chunk, ChunkSectionSnapshot> entry : snapshot.getSnapshots().entrySet()) {
            final ChunkSectionSnapshot chunkSnapshot = entry.getValue();
            final ChunkPos pos = chunkSnapshot.position();

            world.getChunkAtAsync(pos.x, pos.z, true, true)
                    .thenCompose(chunk ->
                            restoreChunkBlockSnapshot(chunk, chunkSnapshot, false)
                                    .thenRun(() -> {
                                        if (updateLighting) {
                                            updateLighting(Set.of(chunk));
                                        }
                                    })
                    );
        }
    }

    @InternalApi
    public static void removeVirtualWorld(VirtualWorld world) {
        loadedWorlds.remove(world);
    }

    /**
     * Request a lighting update for a set of chunks asynchronously.
     *
     * @param chunks set of chunks that need lighting recalculation
     * @return a {@link CompletableFuture} that completes once the lighting task is scheduled
     */
    public static CompletableFuture<Void> updateLighting(final Set<Chunk> chunks) {
        return CompletableFuture.runAsync(() -> LightingService.updateLighting(chunks, true));
    }

    /**
     * Asynchronously restore a saved cuboid snapshot.
     * <p>
     * This schedules chunk restores and completes once all chunks are restored and lighting
     * updates have been triggered for the affected chunks.
     *
     * @param snapshot      cuboid snapshot containing multiple chunk snapshots
     * @param clearEntities whether to clear non-player entities when restoring
     * @return a CompletableFuture that completes when the restore finishes
     */
    public static CompletableFuture<Void> restoreCuboidSnapshotAsync(final CuboidSnapshot snapshot, final boolean clearEntities) {
        return restoreCuboidSnapshotInternal(snapshot, clearEntities);
    }

    /**
     * Restore all chunk snapshots contained in a {@link CuboidSnapshot} synchronously.
     * <p>
     * Each chunk snapshot will be restored and then lighting will be updated for all affected chunks.
     *
     * @param snapshot      snapshot to restore
     * @param clearEntities whether to clear non-player entities during restore
     */
    public static void restoreCuboidSnapshot(final CuboidSnapshot snapshot, final boolean clearEntities) {
        restoreCuboidSnapshotInternal(snapshot, clearEntities);
    }

    private static CompletableFuture<Void> restoreCuboidSnapshotInternal(final CuboidSnapshot snapshot, final boolean clearEntities) {
        if (snapshot.getSnapshots().isEmpty()) {
            // log("Snapshot is empty, skipping restore.");
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Chunk>> chunkFutures = new ArrayList<>(snapshot.getSnapshots().size());
        List<CompletableFuture<Void>> restoreFutures = new ArrayList<>(snapshot.getSnapshots().size());

        for (Map.Entry<Chunk, ChunkSectionSnapshot> entry : snapshot.getSnapshots().entrySet()) {
            ChunkSectionSnapshot section = entry.getValue();
            ChunkPos pos = section.position();
            World world = entry.getKey().getWorld();

            CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsync(pos.x, pos.z, true, true);
            chunkFutures.add(chunkFuture);

            restoreFutures.add(chunkFuture.thenCompose(chunk ->
                    restoreChunkBlockSnapshot(chunk, section, clearEntities)
            ));
        }

        return CompletableFuture.allOf(restoreFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> chunkFutures.stream()
                        .map(f -> f.getNow(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .thenAccept(chunks -> LightingService.updateLighting(chunks, false));
    }

    public static JavaPlugin getPlugin() {
        return plugin;
    }

    @InternalApi
    public static void log(String message) {
        plugin.getLogger().info(message);
    }
}
