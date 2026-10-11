package dev.skyboxy.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarketDatabaseRecoveryTest {
    @TempDir Path directory;

    @Test
    void shouldPageAllClaimsByStableKeysWithoutSkippingAfterDelivery() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("all-claims.db").toFile())){
            UUID owner=UUID.randomUUID(),other=UUID.randomUUID();java.util.List<Long> keys=new java.util.ArrayList<>();
            for(int n=0;n<94;n++)keys.add(database.storeRecovery(owner,"booth", "lease-"+(n/30),"source-"+n,0,new byte[]{1}));
            database.storeRecovery(other,"booth","other-lease","foreign",0,new byte[]{1});
            long end=database.recoveryUpperBound(owner);
            assertEquals(keys.getLast().longValue(),end);
            assertEquals(keys.subList(0,45),database.recoveryKeysAfter(owner,0,end,45));
            for(long key:keys.subList(0,45)){assertTrue(database.beginClaim(key,owner));assertTrue(database.completeClaim(key,owner));}
            assertEquals(keys.subList(45,90),database.recoveryKeysAfter(owner,keys.get(44),end,45));
            database.storeRecovery(owner,"booth","new-lease","new-during-claim",0,new byte[]{1});
            assertEquals(keys.subList(90,94),database.recoveryKeysAfter(owner,keys.get(89),end,45));
            assertTrue(database.recoveryKeysAfter(owner,end,end,45).isEmpty());
            assertTrue(database.recoveryKeysAfter(UUID.randomUUID(),0,end,45).isEmpty());
        }
    }

    @Test
    void shouldExcludePendingAndCompletedEntriesFromBoundedAllClaims() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("all-claim-states.db").toFile())){
            UUID owner=UUID.randomUUID();long pending=database.storeRecovery(owner,"booth","lease","pending",0,new byte[]{1});
            long complete=database.storeRecovery(owner,"booth","lease","complete",0,new byte[]{1});
            long available=database.storeRecovery(owner,"booth","lease","available",0,new byte[]{1});
            assertTrue(database.beginClaim(pending,owner));assertTrue(database.beginClaim(complete,owner));assertTrue(database.completeClaim(complete,owner));
            assertEquals(java.util.List.of(available),database.recoveryKeysAfter(owner,0,available,45));
            assertTrue(database.reconcileClaim(pending,owner,false));
            assertEquals(java.util.List.of(pending,available),database.recoveryKeysAfter(owner,0,available,45));
        }
    }

    @Test
    void shouldRejectUnboundedRecoveryKeyQueries() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("all-claim-bounds.db").toFile())){
            UUID owner=UUID.randomUUID();
            for(int limit:new int[]{-1,0,46,Integer.MAX_VALUE})org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    ()->database.recoveryKeysAfter(owner,0,1,limit));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->database.recoveryKeysAfter(owner,-1,1,45));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->database.recoveryKeysAfter(owner,0,-1,45));
            assertEquals(0,database.recoveryUpperBound(owner));assertTrue(database.recoveryKeysAfter(owner,0,0,45).isEmpty());
        }
    }

    @Test
    void shouldJournalPartialDeliveryAndRemainderTogetherAcrossRestart() throws Exception {
        Path file=directory.resolve("partial.db");UUID owner=UUID.randomUUID();long entry;
        byte[] original={64,9,8},delivery={4,9,8},remainder={60,9,8};
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            entry=database.storeRecovery(owner,"booth","lease","container:1:2:3",0,original);
            assertTrue(database.beginClaim(entry,owner,original,delivery,remainder));
            assertEquals(1,database.recoveryCount(owner));
            assertEquals(java.util.List.of(entry),database.inFlightClaims(owner));
        }
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            assertTrue(database.reconcileClaim(entry,owner,false));
            assertEquals(2,database.recoveryCount(owner));
            assertEquals(entry,database.storeRecovery(owner,"booth","lease","container:1:2:3",0,original));
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);
                var query=connection.createStatement();var rows=query.executeQuery("SELECT item FROM recovery ORDER BY id")){
                assertTrue(rows.next());org.junit.jupiter.api.Assertions.assertArrayEquals(delivery,rows.getBytes(1));
                assertTrue(rows.next());org.junit.jupiter.api.Assertions.assertArrayEquals(remainder,rows.getBytes(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void shouldRejectEmptyPartialClaimPayloadsWithoutLockingTheEntry() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("empty-partial.db").toFile())){
            UUID owner=UUID.randomUUID();byte[] item={64};long entry=database.storeRecovery(owner,"booth","lease","source",0,item);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    ()->database.beginClaim(entry,owner,item,new byte[0],new byte[]{64}));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    ()->database.beginClaim(entry,owner,item,new byte[]{64},new byte[0]));
            assertEquals(1,database.recoveryCount(owner));assertTrue(database.inFlightClaims(owner).isEmpty());
        }
    }

    @Test
    void shouldPreventStaleOrForeignRequestsFromSplittingAClaimTwice() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("stale-partial.db").toFile())){
            UUID owner=UUID.randomUUID();byte[] item={64};long entry=database.storeRecovery(owner,"booth","lease","source",0,item);
            assertFalse(database.beginClaim(entry,UUID.randomUUID(),item,new byte[]{4},new byte[]{60}));
            assertFalse(database.beginClaim(entry,owner,new byte[]{63},new byte[]{4},new byte[]{59}));
            assertEquals(1,database.recoveryCount(owner));
            assertTrue(database.beginClaim(entry,owner,item,new byte[]{4},new byte[]{60}));
            assertFalse(database.beginClaim(entry,owner,item,new byte[]{4},new byte[]{60}));
            assertTrue(database.completeClaim(entry,owner));assertEquals(1,database.recoveryCount(owner));
        }
    }

    @Test
    void shouldRollbackTheClaimWhenItsRemainderCannotBeStored() throws Exception {
        Path file=directory.resolve("failed-partial.db");UUID owner=UUID.randomUUID();byte[] item={64,9};
        try(MarketDatabase database=new MarketDatabase(file.toFile())){
            long entry=database.storeRecovery(owner,"booth","lease","source",0,item);
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var statement=connection.createStatement()){
                statement.execute("CREATE TRIGGER reject_remainder BEFORE INSERT ON recovery WHEN NEW.source LIKE 'claim-remainder:%' BEGIN SELECT RAISE(ABORT,'injected remainder failure'); END");
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    ()->database.beginClaim(entry,owner,item,new byte[]{4,9},new byte[]{60,9}));
            assertEquals(1,database.recoveryCount(owner));assertTrue(database.inFlightClaims(owner).isEmpty());
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var query=connection.createStatement();
                var rows=query.executeQuery("SELECT item FROM recovery")){
                assertTrue(rows.next());org.junit.jupiter.api.Assertions.assertArrayEquals(item,rows.getBytes(1));assertFalse(rows.next());
            }
        }
    }

    @Test
    void shouldExposeOnlyTheOwnersCurrentAvailablePayload() throws Exception {
        try(MarketDatabase database=new MarketDatabase(directory.resolve("payload.db").toFile())){
            UUID owner=UUID.randomUUID();byte[] payload={64,7,9};long entry=database.storeRecovery(owner,"booth","lease","source",0,payload);
            org.junit.jupiter.api.Assertions.assertArrayEquals(payload,database.recoveryPayload(entry,owner));
            org.junit.jupiter.api.Assertions.assertNull(database.recoveryPayload(entry,UUID.randomUUID()));
            assertTrue(database.beginClaim(entry,owner));org.junit.jupiter.api.Assertions.assertNull(database.recoveryPayload(entry,owner));
            assertTrue(database.completeClaim(entry,owner));org.junit.jupiter.api.Assertions.assertNull(database.recoveryPayload(entry,owner));
        }
    }

    @Test
    void shouldKeepUnfinishedClaimAvailableAfterDatabaseRestart() throws Exception {
        Path file = directory.resolve("market.db");
        UUID owner = UUID.randomUUID();
        long entry;

        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            entry = database.storeRecovery(owner, "booth", "lease", "container:1:2:3", 0, new byte[]{1});
            assertTrue(database.beginClaim(entry, owner));
            assertEquals(0, database.recoveryCount(owner));
        }

        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            assertEquals(java.util.List.of(entry), database.inFlightClaims(owner));
            assertTrue(database.reconcileClaim(entry, owner, false));
            assertEquals(1, database.recoveryCount(owner));
            assertTrue(database.beginClaim(entry, owner));
            assertTrue(database.completeClaim(entry, owner));
            assertFalse(database.beginClaim(entry, owner));
            assertEquals(0, database.recoveryCount(owner));
        }
    }

    @Test
    void shouldFinalizeAReceiptFoundInPlayerInventoryAfterRestart() throws Exception {
        Path file = directory.resolve("receipt.db");UUID owner = UUID.randomUUID();long entry;
        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            entry = database.storeRecovery(owner, "booth", "lease", "item:1", 0, new byte[]{1});
            assertTrue(database.beginClaim(entry, owner));
        }
        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            assertTrue(database.reconcileClaim(entry, owner, true));
            assertTrue(database.claimCompleted(entry, owner));
            assertEquals(0, database.recoveryCount(owner));
        }
    }

    @Test
    void shouldKeepADurablyDeliveredClaimCompletedAcrossRestart() throws Exception {
        Path file = directory.resolve("delivered.db");UUID owner = UUID.randomUUID();long entry;
        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            entry = database.storeRecovery(owner, "booth", "lease", "container:1:2:3", 0, new byte[]{1});
            assertTrue(database.beginClaim(entry, owner));
            assertTrue(database.completeClaim(entry, owner));
            assertFalse(database.reconcileClaim(entry, owner, false));
        }
        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            assertTrue(database.claimCompleted(entry, owner));
            assertEquals(0, database.recoveryCount(owner));
            assertFalse(database.beginClaim(entry, owner));
        }
    }

    @Test
    void shouldDeduplicateRecoverySnapshotsByLeaseSourceAndSlot() throws Exception {
        try (MarketDatabase database = new MarketDatabase(directory.resolve("dedupe.db").toFile())) {
            UUID owner = UUID.randomUUID();
            long first = database.storeRecovery(owner, "booth", "lease", "entity:item", 0, new byte[]{1, 2});
            long retry = database.storeRecovery(owner, "booth", "lease", "entity:item", 0, new byte[]{3, 4});

            assertEquals(first, retry);
            assertEquals(1, database.recoveryCount(owner));
        }
    }

    @Test
    void shouldMigrateThePreviousBooleanClaimLedgerWithoutReopeningDeliveredRows() throws Exception {
        Path file = directory.resolve("legacy.db");
        UUID owner = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE recovery(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,booth TEXT NOT NULL,lease TEXT NOT NULL,source TEXT NOT NULL,slot INTEGER NOT NULL,item BLOB NOT NULL,claimed INTEGER NOT NULL DEFAULT 0,UNIQUE(lease,source,slot))");
            }
            try (var insert = connection.prepareStatement("INSERT INTO recovery(owner,booth,lease,source,slot,item,claimed) VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, owner.toString());insert.setString(2, "booth");insert.setString(3, "open");insert.setString(4, "a");insert.setInt(5, 0);insert.setBytes(6, new byte[]{1});insert.setInt(7, 0);insert.executeUpdate();
                insert.setString(3, "closed");insert.setString(4, "b");insert.setInt(7, 1);insert.executeUpdate();
            }
        }

        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            assertEquals(1, database.recoveryCount(owner));
            assertTrue(database.beginClaim(1, owner));
            assertFalse(database.beginClaim(2, owner));
        }
    }

    @Test
    void shouldPersistMonotonicRestoreCursorAcrossRestart() throws Exception {
        Path file = directory.resolve("reset-cursor.db");
        Booth booth = new Booth("booth", "world", "normal", 0, 64, 0, 11, 11, 11, 0);
        booth.owner = UUID.randomUUID();
        booth.leaseId = "lease";

        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            database.save(booth);
            database.beginReset(booth);
            database.resetPhase(booth, "RESTORING");
            database.resetCursor(booth, 128);
            assertEquals(128, database.resets().getFirst().restoreCursor());
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> database.resetCursor(booth, 64));
        }

        try (MarketDatabase database = new MarketDatabase(file.toFile())) {
            MarketDatabase.Reset recovered = database.resets().getFirst();
            assertEquals("RESTORING", recovered.phase());
            assertEquals(128, recovered.restoreCursor());
        }
    }

    @Test
    void shouldRestartRestoreCursorWhenEnteringRestoringPhase() throws Exception {
        Booth booth = new Booth("booth", "world", "normal", 0, 64, 0, 11, 11, 11, 0);
        booth.owner = UUID.randomUUID();
        booth.leaseId = "lease";
        try (MarketDatabase database = new MarketDatabase(directory.resolve("reset-phase.db").toFile())) {
            database.save(booth);
            database.beginReset(booth);
            database.resetPhase(booth, "RESTORING");
            database.resetCursor(booth, 128);
            database.resetPhase(booth, "RESTORING");
            assertEquals(0, database.resets().getFirst().restoreCursor());
        }
    }
}
