package dev.skyboxy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MarketDatabaseRentalTest {
    @TempDir Path directory;

    @Test
    void shouldCommitLeaseCooldownAndAuditAsOneDatabaseOperation() throws Exception {
        try (MarketDatabase database = new MarketDatabase(directory.resolve("rental.db").toFile())) {
            Booth booth = new Booth("booth", "world", "normal", 1, 2, 3, 11, 11, 11, 0);
            database.save(booth);
            UUID owner = UUID.randomUUID();
            long expiry = 123456789L;
            long cooldownUntil = 987654321L;

            database.commitRental(booth, owner, "lease-1", expiry, cooldownUntil);

            Booth stored = database.loadBooths().getFirst();
            assertEquals(owner, stored.owner);
            assertEquals("lease-1", stored.leaseId);
            assertEquals(expiry, stored.expiry);
            assertEquals(cooldownUntil, database.cooldown(owner, "normal"));
        }
    }

    @Test
    void shouldKeepLeaseExpiryAndAuditInOneDatabaseOperationOnRenewal() throws Exception {
        try (MarketDatabase database = new MarketDatabase(directory.resolve("renewal.db").toFile())) {
            Booth booth = new Booth("booth", "world", "normal", 1, 2, 3, 11, 11, 11, 0);
            booth.owner = UUID.randomUUID();booth.leaseId = "lease-1";booth.expiry = 100L;
            database.save(booth);

            database.commitRenewal(booth, 200L, booth.owner);

            assertEquals(200L, database.loadBooths().getFirst().expiry);
        }
    }
}
