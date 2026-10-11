package dev.skyboxy.market;

import java.util.*;

/** Chunk-bucket index for the hot location-to-booth lookup used by protection events. */
final class BoothSpatialIndex {
    private record ChunkKey(String world, int x, int z) {}

    private final Map<ChunkKey, List<Booth>> chunks = new HashMap<>();
    private final Map<String, Booth> indexed = new HashMap<>();

    void add(Booth booth) {
        Objects.requireNonNull(booth, "booth");
        if (indexed.putIfAbsent(booth.id, booth) != null) return;
        for (int chunkX = booth.x >> 4; chunkX <= (booth.x + booth.width - 1) >> 4; chunkX++) {
            for (int chunkZ = booth.z >> 4; chunkZ <= (booth.z + booth.length - 1) >> 4; chunkZ++) {
                chunks.computeIfAbsent(new ChunkKey(booth.world, chunkX, chunkZ), ignored -> new ArrayList<>()).add(booth);
            }
        }
    }

    void remove(Booth booth) {
        if (booth == null) return;
        Booth existing = indexed.remove(booth.id);
        if (existing == null) return;
        for (int chunkX = existing.x >> 4; chunkX <= (existing.x + existing.width - 1) >> 4; chunkX++) {
            for (int chunkZ = existing.z >> 4; chunkZ <= (existing.z + existing.length - 1) >> 4; chunkZ++) {
                ChunkKey key = new ChunkKey(existing.world, chunkX, chunkZ);
                List<Booth> bucket = chunks.get(key);
                if (bucket == null) continue;
                bucket.removeIf(candidate -> candidate.id.equals(booth.id));
                if (bucket.isEmpty()) chunks.remove(key);
            }
        }
    }

    void clear() {
        chunks.clear();
        indexed.clear();
    }

    Booth at(String world, int x, int y, int z) {
        if (world == null) return null;
        List<Booth> candidates = chunks.get(new ChunkKey(world, x >> 4, z >> 4));
        if (candidates == null) return null;
        for (Booth booth : candidates) {
            if (x >= booth.x && x < booth.x + booth.width
                    && y >= booth.y && y < booth.y + booth.height
                    && z >= booth.z && z < booth.z + booth.length) return booth;
        }
        return null;
    }
}
