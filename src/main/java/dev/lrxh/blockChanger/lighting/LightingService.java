package dev.lrxh.blockChanger.lighting;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LightingService {
    private static JavaPlugin plugin;

    public static void setPlugin(JavaPlugin plugin) {
        LightingService.plugin = plugin;
    }

    public static void updateLighting(final Set<Chunk> chunks, final boolean refresh) {
        if (chunks == null || chunks.isEmpty()) return;
        final Chunk firstChunk = chunks.iterator().next();
        final ServerLevel world = ((org.bukkit.craftbukkit.CraftWorld) firstChunk.getWorld()).getHandle();
        final World bukkitWorld = firstChunk.getWorld();
        final List<ChunkPos> chunkPositions = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            chunkPositions.add(new ChunkPos(chunk.getX(), chunk.getZ()));
        }
        if (refresh) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Chunk chunk : chunks) {
                    bukkitWorld.refreshChunk(chunk.getX(), chunk.getZ());
                }
            });
        }
        world.getChunkSource().getLightEngine().starlight$serverRelightChunks(
                chunkPositions,
                chunkPos -> {},
                value -> {}
        );
    }
}