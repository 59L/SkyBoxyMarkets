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
| QuickShop-Hikari | `6.3.0.3` | Optional, reflection-only. Startup checks `bukkitLocation`, `isSelling` and the purchase event's `getBalance`; any mismatch disables the provider. |
| ChestShop | `3.12.2` (build 353) | Optional, reflection-only. Startup checks the sign helpers and the transaction and shop-creation events. |
| mc-PlayerShop | `1.5.1` exactly | Optional; it has no public API, so the provider reflects on internals and refuses any other version.  |

## Runtime evidence

| Component | Isolated smoke version | Result |
|---|---|---|
| Paper | `26.2-121-main` (`26.2.build.121-stable`) | Plugin enabled; both schematics/restoration rotations, menus, focused protection/recovery, payment repair, and reset restart checks passed. This is 12 builds behind the pinned compile API. |
| Java | `25.0.4.1` | Paper server launched. |
| FAWE | `2.15.2+e9ed0d1` | Loaded. Both schematics restored at rotations 0–3 through the WorldEdit Bukkit world adapter. FAWE's edit-session queue is bypassed because it raised `IllegalAccessError` on this Paper build. |
| WorldGuard | `7.0.17+2370-e42d8bc` | Loaded; the scanner used the `marketmain` region. Full production flag/event-order acceptance was not run. |
| Vault | `1.7.3-b131` | Loaded. The smoke suite exercised both a test-only provider and the real EssentialsX provider below. |
| EssentialsX | `2.22.0` | Real `EssentialsX Economy` provider was discovered; register/unregister, rent, renewal, and wall charge callbacks passed against a synthetic account. Essentials warned this Paper version is unsupported; this is API evidence, not a production compatibility certification. |
| QuickShop-Hikari | `6.3.0.3`, SHA-256 `EB65B63D803B2C61BE6827F803572BA71F56BBAD9F1CD5959B9B746DF0669C3A` (Modrinth) | Starts on Paper 26.2-124 (display items off without ProtocolLib). Shop in a rented booth, visitor purchase via `ShopManager.actionSelling`, sale counted once with tax on buyer or owner, reset deleted the shop and journaled stock once. Fails closed when absent. |
| ChestShop | `3.12.2` build 353, SHA-256 `633D0C761440A70CCE447E98E917C5F3A64527D75FDB4FAE1E415CD2E82F13A2` (Modrinth) | Same version label as the live server's `2465CC20…` JAR but different bytes; the live JAR was not tested. Creation rules, double-chest shop, purchase counted once, denied-booth click cancelled before trade, reset removed the sign and journaled stock once. |
| mc-PlayerShop | `1.5.1`, SHA-256 `26B020564A553AA909073B8B88F3512B7EB030796BB38726BC511CA0CCDB697C` (Modrinth) | Downloads its runtime libraries on first start. Internals verified; menu blocked in a denied booth; purchase counted once from its transaction records; database-held stock journaled once on reset, including after a simulated crash between journal and deletion. |
| Paper (Claude machine) | `26.2-124` on Java `25.0.2` | All of the above plus the original acceptance and the five-start sequence, with no shop plugin, each alone, and all three together. |

The final isolated suite passed 35 JUnit tests and Paper checks for both style schematics/four rotations, all 16 wall colors/four rotations, menu selection and owner search, role/access and direct/indirect protection cases, item/recovery receipts, payment-intent review and injected refund outcomes, repeated hologram refresh, and QuickShop absence. Reset restoration stayed within the 17-block-per-tick test budget. Two clean server restarts recovered pending resets from `SNAPSHOTTING` and from `RESTORING` at a durable cursor of 32, with booth chunks unloaded before startup. A separate run deliberately halted the JVM after a restore batch had flushed and its SQLite cursor advanced; the next startup completed the reset with the recovery row retained once. This validates one process-termination point, not OS power loss or every crash boundary. A synthetic 1,000-booth index check measured 100,000 lookups in 36 ms against a 5,000 ms budget.

Partial-claim checks also passed stack merging, durable remainders, stale/full-inventory requests, native item/page actions, grouped multi-slot/keep-inventory/selected-kept death cleanup, injected ledger finalization failures, reconnect locking, and a failed player save before ledger reopening. Display lore/action tags did not enter the delivered stock.

Whole-storage claim checks passed 94 entries across three pages/multiple past leases, stable keyset paging with a fixed starting cutoff, partial leftovers, fitting entries beyond a no-fit page, duplicate/concurrent actions, and at most one receipt delivery per tick per player. Paper fault fixtures verified stop/lock/reconcile/retry after a saved receipt failed to finalize. Disconnect tests reject queued page and direct delivery for offline players. Permission revocation, stale page actions, shutdown cancellation/resume and later-menu preservation also passed.

The test harness uses synthetic player objects and a scratch world; no human client was connected. Claim save callbacks and reconnects are simulated, so real player-file crash behavior remains open. Essentials used a synthetic account. The test-only Vault provider injected database/refund failures; those fault cases were not run through Essentials. These results do not represent a production economy or world.

## Compatibility and acceptance still to establish

- Repeat startup, rent, renew, wall charge/refund, schematic restore, and reset checks on the target production Paper, WorldEdit/FAWE, WorldGuard, Vault, and economy-provider versions. Resolve Essentials' unsupported-version warning by testing a supported server/provider combination.
- Re-run ChestShop against the live server's exact `2465CC20…` JAR. Run all three shop plugins with human clients, ProtocolLib (QuickShop display items), and the production WorldGuard flags. Inject a real JVM crash between mc-PlayerShop's journal and deletion; the current check repeats the journal in-process.
- Test all protection roles/actions and WorldGuard flag/event ordering with actual player sessions and the production region configuration, including the remaining redstone and third-party shop boundaries.
- Inject abrupt process/power loss across every reset and recovery-claim persistence boundary; test custom item components and unusual block entities.
- Measure reset snapshotting, chunk loading, and batched restoration TPS on production-sized booths and hardware.
- Run real-player GUI/teleport checks and complete style-version/per-booth-override workflows before production use.

## Cross-play (proxy, Bedrock, older Java)

The intended network layout, plugin settings and acceptance list are in [deploy/crossplay/README.md](../deploy/crossplay/README.md). None of it has been run: no Velocity, Geyser, Floodgate, LibreLogin or ViaVersion build has been tested with this plugin, and no Bedrock or older Java client has opened its menus.
