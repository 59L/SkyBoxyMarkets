# Compatibility and test matrix

## Build target

| Component | Pinned build version | Notes |
|---|---|---|
| Java | 25 | Gradle toolchain target. |
| Gradle | 9.2.1 | Checked-in wrapper with checksum verification. |
| Paper API | `26.2.build.133-stable` | Compile and test classpath. |
| WorldEdit | `7.4.0` | Compile-only server API. |
| WorldGuard | `7.0.18` | Compile-only server API. |
| Vault API | `1.7` | Compile-only; the server must provide Vault and an economy provider for charged actions. |
| SQLite JDBC | `3.50.3.0` | Bundled in the shaded JAR. |
| QuickShop-Hikari | Not pinned | Optional reflection-only bridge; exact supported release is not established. |

## Runtime evidence

| Component | Isolated smoke version | Result |
|---|---|---|
| Paper | `26.2-121-main` (`26.2.build.121-stable`) | Plugin enabled; both schematics/restoration rotations, menus, focused protection/recovery, payment repair, and reset restart checks passed. This is 12 builds behind the pinned compile API. |
| Java | `25.0.4.1` | Paper server launched. |
| FAWE | `2.15.2+e9ed0d1` | Loaded. Both schematics restored at rotations 0–3 through the WorldEdit Bukkit world adapter. FAWE's edit-session queue is bypassed because it raised `IllegalAccessError` on this Paper build. |
| WorldGuard | `7.0.17+2370-e42d8bc` | Loaded; the scanner used the `marketmain` region. Full production flag/event-order acceptance was not run. |
| Vault | `1.7.3-b131` | Loaded. The smoke suite exercised both a test-only provider and the real EssentialsX provider below. |
| EssentialsX | `2.22.0` | Real `EssentialsX Economy` provider was discovered; register/unregister, rent, renewal, and wall charge callbacks passed against a synthetic account. Essentials warned this Paper version is unsupported; this is API evidence, not a production compatibility certification. |
| QuickShop-Hikari | Not installed | Bridge reported unavailable and failed closed; shop deletion and sale accounting were not exercised. |

The final isolated suite passed 35 JUnit tests and Paper checks for both style schematics/four rotations, all 16 wall colors/four rotations, menu selection and owner search, role/access and direct/indirect protection cases, item/recovery receipts, payment-intent review and injected refund outcomes, repeated hologram refresh, and QuickShop absence. Reset restoration stayed within the 17-block-per-tick test budget. Two clean server restarts recovered pending resets from `SNAPSHOTTING` and from `RESTORING` at a durable cursor of 32, with booth chunks unloaded before startup. A separate run deliberately halted the JVM after a restore batch had flushed and its SQLite cursor advanced; the next startup completed the reset with the recovery row retained once. This validates one process-termination point, not OS power loss or every crash boundary. A synthetic 1,000-booth index check measured 100,000 lookups in 36 ms against a 5,000 ms budget.

Partial-claim checks also passed stack merging, durable remainders, stale/full-inventory requests, native item/page actions, grouped multi-slot/keep-inventory/selected-kept death cleanup, injected ledger finalization failures, reconnect locking, and a failed player save before ledger reopening. Display lore/action tags did not enter the delivered stock.

Whole-storage claim checks passed 94 entries across three pages/multiple past leases, stable keyset paging with a fixed starting cutoff, partial leftovers, fitting entries beyond a no-fit page, duplicate/concurrent actions, and at most one receipt delivery per tick per player. Paper fault fixtures verified stop/lock/reconcile/retry after a saved receipt failed to finalize. Disconnect tests reject queued page and direct delivery for offline players. Permission revocation, stale page actions, shutdown cancellation/resume and later-menu preservation also passed.

The test harness uses synthetic player objects and a scratch world; no human client was connected. Claim save callbacks and reconnects are simulated, so real player-file crash behavior remains open. Essentials used a synthetic account. The test-only Vault provider injected database/refund failures; those fault cases were not run through Essentials. These results do not represent a production economy or world.

## Compatibility and acceptance still to establish

- Repeat startup, rent, renew, wall charge/refund, schematic restore, and reset checks on the target production Paper, WorldEdit/FAWE, WorldGuard, Vault, and economy-provider versions. Resolve Essentials' unsupported-version warning by testing a supported server/provider combination.
- Test QuickShop-Hikari API discovery, successful-purchase accounting, shop deletion, and reset retry against the exact target JAR. Until then, the bridge is unavailable and sales totals are not authoritative.
- Test all protection roles/actions and WorldGuard flag/event ordering with actual player sessions and the production region configuration, including the remaining redstone and third-party shop boundaries.
- Inject abrupt process/power loss across every reset and recovery-claim persistence boundary; test custom item components and unusual block entities.
- Measure reset snapshotting, chunk loading, and batched restoration TPS on production-sized booths and hardware.
- Run real-player GUI/teleport checks and complete style-version/per-booth-override workflows before production use.
