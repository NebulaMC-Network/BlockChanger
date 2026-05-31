package dev.lrxh.blockChanger.world;

import dev.lrxh.blockChanger.BlockChanger;
import dev.lrxh.blockChanger.snapshot.ChunkSectionSnapshot;
import dev.lrxh.blockChanger.snapshot.CuboidSnapshot;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@SuppressWarnings({"unused"})
public class VirtualWorld {
    private final ServerLevel level;

    public VirtualWorld(ServerLevel level) {
        this.level = level;
    }

    public World getWorld() {
        return level.getWorld();
    }

    public void unload() {
        try {
            level.getChunkSource().getDataStorage().close();
        } catch (Exception ignored) {}

        try {
            level.moonrise$getChunkTaskScheduler().chunkHolderManager.close(false, false);
        } catch (Exception ignored) {}

        try {
            level.levelStorageAccess.close();
        } catch (Exception ignored) {}

        MinecraftServer.getServer().removeLevel(level);
        BlockChanger.removeVirtualWorld(this);

        Path worldPath = MinecraftServer.getServer()
                .server.getWorldContainer()
                .toPath()
                .resolve(level.getWorld().getName());

        CompletableFuture.runAsync(() -> {
            if (Files.exists(worldPath)) {
                try (Stream<Path> paths = Files.walk(worldPath)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    BlockChanger.log(e.getMessage());
                                }
                            });
                } catch (IOException e) {
                    BlockChanger.log(e.getMessage());
                }
            }
        });
    }

    public CompletableFuture<Void> restore(CuboidSnapshot snapshot) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<Chunk, ChunkSectionSnapshot> entry : snapshot.getSnapshots().entrySet()) {
            ChunkSectionSnapshot chunkSnapshot = entry.getValue();
            ChunkPos pos = chunkSnapshot.position();

            futures.add(getWorld()
                    .getChunkAtAsync(pos.x, pos.z, true, true)
                    .thenCompose(chunk -> BlockChanger.restoreChunkBlockSnapshot(chunk, chunkSnapshot, true))
            );
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<Void> restoreAndResync(CuboidSnapshot snapshot, List<ServerPlayer> players) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<Chunk, ChunkSectionSnapshot> entry : snapshot.getSnapshots().entrySet()) {
            ChunkSectionSnapshot chunkSnapshot = entry.getValue();
            ChunkPos pos = chunkSnapshot.position();

            futures.add(
                getWorld().getChunkAtAsync(pos.x, pos.z, true, true)
                    .thenCompose(chunk ->
                        BlockChanger.restoreChunkBlockSnapshot(chunk, chunkSnapshot, true)
                            .thenRunAsync(() -> {
                                LevelChunk levelChunk = (LevelChunk) ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);
                                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                                    levelChunk,
                                    level.getLightEngine(),
                                    null,
                                    null
                                );
                                for (ServerPlayer player : players) {
                                    player.connection.send(packet);
                                }
                            }, BlockChanger.EXECUTOR)
                    )
            );
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public void paste(CuboidSnapshot snapshot) {
        BlockChanger.paste(getWorld(), snapshot, false);
    }
}
