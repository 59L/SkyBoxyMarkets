# Reset and item recovery

## Reset order

An expiration or staff reset first creates a durable reset row and marks the booth as resetting. The plugin then commits recovery entries in bounded SQLite batches before changing the booth world. It records:

- One item for each tracked player-placed block, with the placed hand item's serialized metadata when available. Old material-only rows fall back to one base-material item.
- Each non-air item stack from container block inventories inside the booth cuboid. A double chest's combined 54-slot view is read once using a stable block coordinate; a chest pair that crosses the booth boundary aborts reset before restoration.
- Dropped items, item-frame contents, and equipment/hand items from armor stands inside the cuboid. Plugin-owned hologram entities are excluded.

Placed blocks are represented by the placing item with the current Bukkit block state applied to `BlockStateMeta` where supported. Container contents are journaled separately, and the returned container shell is emptied to avoid duplicating shulker contents. This does not guarantee every resulting tile-entity field: edited sign text and state changes not represented in a block item's metadata may not be recoverable. This remains a fidelity gap.

The recovery uniqueness key is `(lease, source, slot)`. Retrying a `SNAPSHOTTING` reset re-reads the still-present world and ignores entries already committed. Only after the snapshot is finished does the reset enter `RESTORING`, detach supported shops, remove captured entities, and restore the schematic in `market.reset-blocks-per-tick` batches. Each batch flushes world storage before committing its idempotent SQLite cursor; a crash before cursor commit replays the last batch. This adds a synchronous world-save cost during reset. The lease and player decorations are cleared from SQLite only after restoration succeeds. Startup recovery loads the reset booth's chunks itself before snapshot or restore. If its world, style file, or chunk cannot be loaded, the reset row and lease stay intact and the booth remains locked; other reset rows are still attempted.

## Claim order

The `/market recover` menu shows available entries in pages of 45. Item, page, and whole-storage claims deliver as many items as fit in matching stacks and empty storage slots. Any remainder stays in recovery. Each delivery follows this order:

1. Read the owner-scoped available row and compute capacity from the current inventory.
2. In one SQLite transaction, move the claimed portion to delivery-pending and insert an available remainder when needed. The transition compares the exact original payload, so stale requests cannot split it twice. The original source key stays present for reset-snapshot deduplication.
3. Insert or merge the portion with a hidden receipt ID and the new quantity in each affected inventory stack.
4. Save player data while those receipts are present.
5. Mark the claimed portion delivered in SQLite.
6. Remove the receipt markers and save player data again.

The receipt is blocked from ordinary use, dropping, hand swapping, and inventory movement while delivery remains pending. On reconnect, saved tokens are locked before ledger reconciliation; a database failure leaves them locked. A receipt in the player's inventory finalizes the ledger entry; without a receipt, the pending portion becomes available again. A crash after database completion but before cleanup is also reconciled from the completed row.

Death handling groups all stacks carrying the same receipt. For a pending claim, it subtracts only the new quantity from drops, retained inventory, and Paper's selected-kept items, preserving stock that was already in the player's stacks. It saves inventory cleanup before reopening the pending row. A failed save leaves the row pending for reconnect reconciliation. Completed items retain normal death behavior and have both marker fields stripped.

**Claim all storage** is a separate button from **Claim all on page**. It checks every available entry up to the highest entry ID at the start, using owner-scoped keyset pages. Claimed rows disappearing cannot skip later entries. New reset entries and partial remainders are kept for the next pass. Entries that do not fit are skipped so later matching stacks can still be filled. Each tick checks at most 16 entries and performs at most one receipt delivery (two player saves), per player. Duplicate all-claim requests and concurrent item/page claims are blocked. Disconnect, loss of market permission, plugin disable, or a delivery error stops the pass with unclaimed entries intact. Completing a pass refreshes the recovery menu only if the player still has a recovery screen open. The page action remains synchronous for at most 45 entries and needs performance measurement along with the synchronous SQLite/player-save costs.

## Interrupted payments

The `payment_intents` ledger records the player, booth, action, amount, Vault provider, state, and timestamps before a charged action calls Vault. Successful rent, renewal, and wall changes mark the intent `APPLIED` in the same SQLite transaction as the marketplace state. Database failures journal a pending compensation before attempting a provider refund. Provider exceptions/refusals and restart-interrupted states are retained for staff review; startup converts ambiguous `PREPARED`, `CHARGED`, and `REFUND_PENDING` rows to `REVIEW_REQUIRED`.

Use `/market admin` → **Payment Review** to inspect unresolved references. Compare the intent with the provider transaction history and current booth state before recording **No debit confirmed**, **Refund confirmed**, or **Marketplace action confirmed**. These are audit resolutions only: the GUI never sends money or retries a charge/refund. If money must be returned, issue it in the provider's staff workflow, verify the completed transaction, then record the resolution. Vault and SQLite do not share an atomic transaction; a crash during an external economy call may require provider-ledger reconciliation.

## What this does not prove

Partial-claim tests cover atomic portion/remainder splits, exact owner/payload checks, stale requests, rollback after a rejected remainder insert, and database restart. The Paper claim fixtures exercise stack merging, full inventories, ledger-finalization failures, reconnect locking, and player-save failures during death. These fixtures use simulated players and save callbacks; they do not interrupt a real player data file.

Whole-storage tests cover stable bounded keys while entries complete, multiple past leases, owner/state filtering and a fixed starting cutoff. Paper delivered 94 entries across three pages with at most one receipt per tick, reached fitting stock beyond a no-fit page, preserved partial metadata, and rejected duplicate/concurrent actions. Fault/cancellation checks cover saved-receipt finalization failure, reconnect and retry, disconnect, permission revocation, stale page actions, plugin shutdown cleanup and resuming a canceled pass. The fixtures also verify that completion does not replace a menu opened later.

The automated suite covers SQLite claim state, restart reconciliation, migration from the prior `claimed` flag, snapshot deduplication, payment-intent transitions and failed rental commits, reset cursor migration/restart, and consistent backup. A Paper harness with FAWE loaded captures double-chest, nested-shulker, tagged dropped-item, and tagged placed-block contents, verifies that restores stay within a 17-block-per-tick test budget, and resumes after restart from both `SNAPSHOTTING` and a partially restored `RESTORING` cursor. It also injects SQLite and provider refund failures and reconciles the test intent through the admin menu. For reset restart tests, the harness unloads affected booth chunks before production recovery runs. A separate staged reset flushed one batch, committed the cursor, then terminated the JVM with `Runtime.halt`; the next startup completed the reset and found its recovery item exactly once. This does not simulate abrupt power loss, a real player session, or every commit boundary. Full custom-item integrations, third-party inventory plugins, unusual block entities, entity inventories, and QuickShop data are not certified. Snapshot scanning, chunk loading, synchronous world flushes, and synchronous database work still need TPS measurement.

If a recovery item remains marked pending, keep it in the player's inventory and ask them to reconnect so the receipt can reconcile. Check server logs and back up the complete plugin data directory before any manual repair. Do not delete rows from `recovery` or `resets` to free a booth: those rows may be the only durable copy of player property.

**No production lossless/exactly-once guarantee is claimed. The abrupt JVM test covers one reset batch only; power-loss behavior, player-session claim tests, full metadata acceptance, and the remaining item-ownership cases are open.**
