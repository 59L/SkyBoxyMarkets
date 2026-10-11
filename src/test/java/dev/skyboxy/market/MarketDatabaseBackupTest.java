package dev.skyboxy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarketDatabaseBackupTest {
    @TempDir Path directory;

    @Test
    void shouldCreateAnIntegrityCheckedSnapshotThatRestoresBoothsAndRecoveryRows() throws Exception {
        Path source = directory.resolve("market.db");
        Path backup = directory.resolve("backups").resolve("market-backup.db");
        UUID owner = UUID.randomUUID();

        try (MarketDatabase database = new MarketDatabase(source.toFile())) {
            Booth booth = new Booth("booth", "world", "normal", 10, 64, 20, 8, 6, 9, 0);
            database.save(booth);
            database.storeRecovery(owner, booth.id, "lease", "item:source", 0, new byte[]{1, 2, 3});

            database.backup(backup.toFile());
            assertEquals("ok", database.integrityCheck());
            assertTrue(backup.toFile().isFile());
            assertThrows(Exception.class, () -> database.backup(backup.toFile()), "Backups must not overwrite an earlier snapshot");
        }

        try (MarketDatabase restored = new MarketDatabase(backup.toFile())) {
            assertEquals(1, restored.loadBooths().size());
            assertEquals(1, restored.recoveryCount(owner));
            assertEquals("ok", restored.integrityCheck());
        }
    }
}
