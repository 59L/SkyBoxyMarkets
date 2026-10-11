# Architecture and state transitions

## Runtime components

- `SkyBoxyMarket` loads styles, schematics, SQLite, services, listeners, and command/menu handlers.
- `StyleTemplates` reads WorldEdit schematics and produces a fixed block-state matcher plus the recolorable wall mask for each configured style.
- `MarketScanner` inspects the configured WorldGuard market region on the server thread. It uses uncommon fixed blocks as anchors, checks candidate boxes at four rotations, and queues only complete matches for registration.
- `MarketDatabase` stores permanent booth locations, rental state, access lists, placed-block records, visits, cooldowns, recovery entries, reset phases, durable payment intents, and audit events.
- `MarketRentals`, `MarketRecovery`, `MarketProtection`, `MarketMenus`, and `MarketHolograms` handle their respective player and world actions. Menus are native Bukkit inventories.
- `QuickShopBridge` is optional and uses reflection to avoid a hard dependency on one QuickShop artifact.
- `BoothSpatialIndex` buckets booths by world and chunk so location checks inspect only booths sharing a chunk, rather than every registered booth.

## Scanner work

The WorldGuard cuboid is scanned in a bounded, incremental task. Anchor positions and template comparisons have separate per-tick budgets; comparisons resume from a cursor. No async task reads blocks. The scanner checks that the full rotated template is inside the region and that its chunks are already loaded. It never force-loads terrain and never modifies blocks.

Matches are grouped by world, box, and style. Existing boxes are kept as-is so a rescan does not replace their IDs or lease state. Overlaps are excluded from registration. The final result reports discoveries, existing matches, overlaps, ambiguities, save failures, and unloaded positions/candidates. A repeat pass starts from the region minimum and is how staff include chunks loaded since a prior scan.

## Persistence and rental changes

SQLite uses WAL mode, `synchronous=FULL`, foreign-key enforcement, and version markers in `schema_migrations`. On startup, additive migrations preserve the older recovery claim flag, create payment-intent schema version 3, and add the durable reset restore cursor in schema version 4. Rent, renewal, wall updates, and their `APPLIED` intent transitions use one SQLite transaction. The provider debit/refund still occurs outside SQLite. Interrupted or ambiguous calls become `REVIEW_REQUIRED`; the admin menu records a staff-verified outcome without retrying an external payment. Admin diagnostics include database integrity and pending reset/recovery/payment counts. `/market backup` uses SQLite `VACUUM INTO` for a consistent database-only snapshot; it does not copy config, schematics, or worlds.

Database calls currently run synchronously from server-thread flows. This is simple and keeps Bukkit model changes ordered, but it needs measurement on larger markets and a future bounded/asynchronous persistence design.

## Reset and recovery transitions

```text
lease active
  -> reset row + booth locked (SNAPSHOTTING)
  -> idempotent recovery snapshot batches committed
  -> phase set to RESTORING
  -> optional shop detachment, entities removed, schematic restored
  -> lease/access/placed-block rows cleared and booth made available atomically
```

If a reset stops during `SNAPSHOTTING`, the next attempt re-reads the still-present world and inserts with unique `(lease, source, slot)` keys. If it stops during `RESTORING`, the journal already exists and the saved cell cursor resumes the schematic paste. Each block batch is flushed to world storage with `World.save(true)` before its SQLite cursor commit. A crash between the world flush and cursor commit replays the last batch safely. Startup recovery loads affected booth chunks before processing a pending reset; a missing world, schematic, or failed chunk load stops that booth's reset while retaining its lease and recovery rows. Other reset rows are still attempted.

Recovery rows use state 0 (available), state 1 (delivery pending), and state 2 (delivered). Partial claims compare the exact owner-scoped payload and atomically store a pending portion plus an available remainder. The original snapshot source key remains reserved, preventing recapture of already split stock. Delivery marks each affected inventory stack with a receipt ID and the newly added quantity, saves player data, completes the portion, removes both markers, and saves again. Pending receipts stay locked when reconciliation fails. Death cleanup groups receipt IDs, preserves pre-existing quantities across drops and retained items, saves the cleaned inventory, then reopens the pending portion. Simulated claim fault checks and actual reset process-halt checks pass; real player-file crash injection is still incomplete.

## Threading and known scale limits

Whole-storage claims use a fixed starting entry ID ceiling and owner/state/ID index. Keyset pages remain stable while completed rows disappear. A pass checks at most 16 entries and performs at most one receipt delivery per tick per player. It stops on disconnect, permission loss or delivery failure, and plugin disable cancels all passes before closing SQLite. Completion refreshes only an open recovery screen. Newly added entries and partial remainders stay available for the next pass. The separate page action remains synchronous for up to 45 entries.

Bukkit block, entity, inventory, and world calls stay on the primary thread. Scanner work and booth registration are budgeted per tick. Clipboard restoration is split across ticks using `market.reset-blocks-per-tick` (default 256, clamped 1–4096) and the SQLite cursor. World flushes, chunk loading, reset snapshot scanning/inventory reads, and database calls remain synchronous; these paths still need representative TPS measurement.

See [SCHEMATICS.md](SCHEMATICS.md), [RECOVERY.md](RECOVERY.md), and [FEATURE_STATUS.md](../FEATURE_STATUS.md) for validation details and release limits.
