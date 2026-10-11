package dev.skyboxy.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class BoothSpatialIndexTest {
    @Test
    void shouldFindBoothsAcrossChunkBoundariesWithoutCrossingWorldOrCuboidEdges() {
        BoothSpatialIndex index = new BoothSpatialIndex();
        Booth booth = new Booth("booth", "market", "normal", 15, 70, -17, 4, 6, 4, 0);
        index.add(booth);

        assertSame(booth, index.at("market", 15, 70, -17));
        assertSame(booth, index.at("market", 18, 75, -14));
        assertNull(index.at("market", 19, 75, -14));
        assertNull(index.at("market", 18, 76, -14));
        assertNull(index.at("other", 18, 75, -14));
    }

    @Test
    void shouldIndexEachBoothOnlyOnceAndClearAllChunkBuckets() {
        BoothSpatialIndex index = new BoothSpatialIndex();
        Booth booth = new Booth("booth", "market", "normal", -16, 64, 0, 2, 4, 2, 0);
        index.add(booth);
        index.add(booth);

        assertSame(booth, index.at("market", -15, 64, 1));
        assertEquals("booth", index.at("market", -15, 64, 1).id);
        index.remove(booth);
        assertNull(index.at("market", -15, 64, 1));
        index.add(booth);
        index.clear();
        assertNull(index.at("market", -15, 64, 1));
    }
}
