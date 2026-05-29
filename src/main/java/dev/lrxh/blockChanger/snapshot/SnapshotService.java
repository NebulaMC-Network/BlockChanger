package dev.lrxh.blockChanger.snapshot;

import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SnapshotService {
    // key: "<world-uuid>:<chunkX>:<chunkZ>"
    private static final ConcurrentHashMap<String, QueuedChunkSnapshot> chunkSnapshots = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> snapshotTimestamps = new ConcurrentHashMap<>();

    private static String makeKey(final UUID worldUUID, final int chunkX, final int chunkZ) {
        return worldUUID.toString() + ":" + chunkX + ":" + chunkZ;
    }

    public static void addSnapshot(final ChunkSectionSnapshot snapshot, final World world) {
        final ChunkPos pos = snapshot.position();
        final String key = makeKey(world.getUID(), pos.x, pos.z);
        chunkSnapshots.put(key, new QueuedChunkSnapshot(world.getName(), snapshot));
        snapshotTimestamps.put(key, System.currentTimeMillis());
    }

    public static QueuedChunkSnapshot getSnapshot(final World world, final ChunkPos position) {
        return chunkSnapshots.get(makeKey(world.getUID(), position.x, position.z));
    }

    public static void removeSnapshot(final World world, final ChunkPos position) {
        final String key = makeKey(world.getUID(), position.x, position.z);
        chunkSnapshots.remove(key);
        snapshotTimestamps.remove(key);
    }

    public static void cleanupOldSnapshots(long maxAgeMillis) {
        final long cutoff = System.currentTimeMillis() - maxAgeMillis;
        for (Map.Entry<String, Long> entry : snapshotTimestamps.entrySet()) {
            final String key = entry.getKey();
            snapshotTimestamps.computeIfPresent(key, (k, ts) -> {
                if (ts < cutoff) {
                    chunkSnapshots.remove(k);
                    return null; // removes from snapshotTimestamps
                }
                return ts;
            });
        }
    }

    public static void clearAll() {
        chunkSnapshots.clear();
        snapshotTimestamps.clear();
    }
}
