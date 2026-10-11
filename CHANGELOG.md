# Changelog

## 2026-10-11 — partial recovery and receipt faults

- Recovery item/page actions claim the quantity that fits, including matching partial stacks. SQLite atomically preserves the claimed portion and any remainder, with owner/payload validation and the original reset source key retained.
- Receipt quantities now distinguish recovered items from pre-existing inventory stock. Multi-slot, keep-inventory and selected-kept death cleanup preserve baseline quantities and save cleanup before reopening the ledger.
- Saved receipts stay locked if reconnect reconciliation encounters a database failure. Claims read their authoritative payload from SQLite instead of trusting displayed menu items.
- Added five unit tests for split/restart accounting, stale/foreign requests, empty payloads, remainder-insert rollback, and owner-scoped payload reads. Clean build: 32 tests, no failures/errors/skips. Paper reproduced the previous failures, then passed the partial-capacity, receipt/death, injected ledger/player-save, and reconnect cases on the rebuilt JAR.
- Latest artifact SHA-256: `2DE05769D4F95F871F12777E242CA6CED4548728A482D7C62CEDC1630E7A1EB5`. Full isolated acceptance and the five-start reset/hard-halt sequence passed. The separate whole-storage claim-all action and the documented release gates remain open.

## 2026-10-11 — resumable restoration and acceptance

- Added schema migration 4 with a monotonic restore cursor and configurable per-tick schematic batches. Each batch calls `World.save(true)` before committing its cursor, so a process crash cannot advance the SQLite cursor ahead of flushed chunks; a crash before cursor commit safely replays the last idempotent batch.
- Added database tests for cursor migration, monotonic updates, and restart persistence. The final clean Java 25 build passes 27 JUnit tests.
- Expanded Paper protection acceptance to cover hopper transfers, explosions, and locked vehicle entry. The isolated FAWE runtime passed the configured 17-block-per-tick budget, restarted from a persisted restore cursor at block 32, and recovered after an intentional JVM halt following a flushed batch.
- Final tested JAR SHA-256: `824029A8D95505DE1A98A59D94265CF86F9EDAC80167EAB7108DFA617F3A3EC0`.
- The release gate remains open for real-player and production-provider checks, full crash/block-entity coverage, style migrations/overrides, and QuickShop-Hikari compatibility.

## 2026-10-10 — tested development snapshot

- Restored both bundled booth schematics in all four orientations with FAWE present by using the WorldEdit clipboard and Bukkit world adapter.
- Made startup resets load their booth chunks before continuing and keep each failed reset journaled for retry.
- Added schema-versioned reset cursors and configurable per-tick schematic restore batches; restart replay begins at the last committed block batch.
- Hardened item recovery for double chests, placed container items, nested shulkers, and death during receipt delivery.
- Added a chunk-based booth index, SQLite integrity/reset/recovery diagnostics, and `/market backup` for consistent database-only snapshots.
- Added SQLite payment intents with startup review for ambiguous charge/refund states and an admin Payment Review GUI. Economy faults are not retried automatically; staff reconcile against the provider ledger.
- Added multi-booth wall/teleport selectors, admin owner search, fail-closed QuickShop API detection, and expanded wall/protection/hologram/spatial acceptance checks.
- Paper acceptance exercised EssentialsX 2.22.0's Vault provider for rent, renewal, and wall charges against a synthetic account. Essentials warned that the tested Paper 26.2 build is unsupported; a test provider also exercised SQLite/refund failure and manual reconciliation.
- Added unit tests for payment state transitions and rollback, plus Paper checks for all 16 wall colors × four rotations, owner/payment review GUI flows, and QuickShop absence.

This is an unsigned development build. Real-player GUI acceptance, compatibility on a supported Paper/economy combination, QuickShop-Hikari validation, complete protection coverage, abrupt power-loss tests, and production-scale TPS checks remain open; see [FEATURE_STATUS.md](FEATURE_STATUS.md).
