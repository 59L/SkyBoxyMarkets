# Changelog

## 2026-10-12 — cross-play preparation (Claude)

- **Cross-play kit** in [deploy/crossplay/](deploy/crossplay/README.md): Velocity with LibreLogin for cracked and premium accounts, Geyser and Floodgate for Bedrock, and ViaVersion/ViaBackwards on the backend for Java 1.21 and newer. Configuration only; nothing has been run on a server yet.
- **Player lookup by name** (booth members, bans, owner search, admin assign) now reads only Paper's local profile cache through `getOfflinePlayerIfCached`. Before, `getOfflinePlayer(name)` could block the main thread on a Mojang lookup and, on an offline-mode backend, guessed a name-derived UUID. It now returns the UUID the player actually joined with, whether premium, cracked or Bedrock. Not yet run on Paper; the artifact SHA-256 is unrecorded until the next local build.

## 2026-10-11 — shop providers and release-gate pass (Claude)

- **Shop provider registry.** QuickShop-Hikari 6.3.0.3, ChestShop 3.12.2 and mc-PlayerShop 1.5.1 coexist, and each one fails closed. Sales are recorded once per `(provider, key)` in the new `shop_sales` table (schema migration 5). Diagnostics show each provider's state, and sales read "unavailable" when no provider is available.
- **QuickShop:** owner revenue is `getBalance`, confirmed from real purchases. Fixed two bugs: the listener saw every QuickShop event, and trades into buying shops counted as revenue.
- **ChestShop:** creation inside a booth is limited to the renter and trusted members, with no admin shops. Only BUY transactions count. Reset removes shop signs.
- **mc-PlayerShop:** pinned to exactly 1.5.1. Reset journals its database-held stock exactly once, then deletes the shop directly. Its menus are blocked in denied booths, because it ignores cancelled clicks. Sales come from its transaction records.
- **Protection fix:** click protection now runs at `LOWEST`. Before, a ChestShop shop in a suspended or denied booth still traded, because ChestShop acts at `LOW`.
- **Release gates:** checked hologram chunk reload, rented-booth redstone, missing-schematic reset safety, the scanner region edge and scan timing (`run.ps1 -Mode gates`).
- Clean build: 39 tests. Artifact SHA-256 `AABDB0D31456E4131184D6612E54AD983D08B7A2C0795E325617B1612888275E`. Five-start sequence passed with no shop plugin, each alone, and all three together.

## 2026-10-11 — whole-storage claim-all

- Added a **Claim all storage** recovery action, separate from **Claim all on page**. It walks every available entry up to the highest ID present when the pass started. Each tick checks at most 16 entries and delivers at most one receipt per player. Items that do not fit stay in recovery.
- Duplicate and concurrent claims are refused. Disconnect, loss of permission, plugin disable or a delivery error stops the pass with entries intact. A queued page action no longer delivers to a disconnected player.
- Clean build: 35 tests, no failures/errors/skips. Paper delivered 94 entries across three pages, and the five-start reset/hard-halt sequence passed.
- Tested artifact SHA-256: `72B260512F7149E2BA8CCE4BC9F982820849095151DB88729D2FA86B9AE8F696`. The release gates in [FEATURE_STATUS.md](FEATURE_STATUS.md) remain open.

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
