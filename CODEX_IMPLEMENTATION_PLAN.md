# SkyBoxyMarket — full implementation and development plan for Codex

## Assignment for Codex

Take this repository from its **uncompiled development snapshot** to a documented, tested, safe Paper/FAWE/WorldGuard marketplace plugin. Review all Java classes and `.schem` assets before making architectural changes. Prioritize correctness and safety over feature quantity. Never claim a feature is complete without a successful build and an appropriate test. Deliver both source and a release JAR only after the acceptance criteria are met.

**Use the current source as a starting point, not a specification of correct implementation.** Existing files include `SkyBoxyMarket.java`, `Models.java`, `StyleTemplates.java`, `MarketScanner.java`, `MarketDatabase.java`, `MarketRentals.java`, `MarketRecovery.java`, `MarketProtection.java`, `MarketHolograms.java`, `MarketMenus.java`, `MarketChatInput.java`, and `QuickShopBridge.java`.

## Product requirements — authoritative

### Market layout and discovery

- Admins build / paste booths themselves. **Never auto-generate additional booths** or paste new stalls during discovery.
- Only consider structures wholly inside the WorldGuard region **`marketmain`** in the configured world.
- The template files currently supplied are `mainMarket2.schem` (**normal**) and `marketboothCorner2.schem` (**premium corner**). Treat any earlier `marketbooth.schem` / `marketboothCorner.schem` as obsolete.
- Support **one to any practical number of booth styles**, each with its own `.schem`, dimensions, rentals, cooldowns, required permission, hologram position, teleport position, wall colors and prices. All styles independently configurable via in-game admin GUIs, optionally supplemented by YAML.
- Match booths independent of orientation at 0, 90, 180, and 270 degrees, including directional block states. No bedrock markers or WorldGuard per-booth regions.
- Structural match: fixed template shell/white concrete floor, with designated **gray-concrete wall positions** accepting any allowed concrete color (regardless of color when first discovered). Ignore or strictly check nonstructural details only via explicit template rules; preserve meaningful structural geometry. Template may contain unusual other blocks (e.g., coral, moss, wart); add admin-selectable strict / excluded block policy only after inspecting actual schematics.
- On first discovery, save stable booth UUID, world, cuboid, rotation, template-style ID/version; never silently remap it on later scans. Existing leases survive server restarts/rescans and decorative modifications. Detect overlap, ambiguous styles, already-registered booths, out-of-region matches; show GUI and chat summary.
- Scanner must not read world blocks off-thread or force-load huge numbers of chunks on tick. Bound scanned blocks and template comparisons per tick and support cancel, progress, resume, and a configurable handling policy for unloaded chunks. Avoid O(world-volume x all-template-blocks) on large regions using rare anchors, palette hashing, spatial indexing and bounded work.
- `marketmain` also contains common paths; per-booth boundaries are **computed from matched schematic**, not manually defined. Protect commons separately. Corner booths need not be manually marked.

### Style management

- At launch two styles: Normal, Premium Corner (8 real premium sites, but don't hardcode number eight).
- Per style: unique name, icon, `.schem` filename, enabled, priority, rental price, renewal price, lease length, grace period, cooldown after closure, wall-change cooldown/cost, allowed wall colors, max per-player active leases, optional permission, hologram and TP transforms, configurable role permissions and restorable structural mask.
- Default globally max one booth per player, configurable; avoid unbounded leases unless explicitly permitted.
- Admin GUI: create, configure, clone, enable/disable, retire styles. Import new schematic files from plugin directory; validate file/path before accepting. Updates to existing styles must be versioned; never migrate or destroy occupied booths just because `.schem` changed.
- Admin overrides per single booth where needed (rent settings, style assignment when applicable, TP/hologram offsets). Validate geometry compatibility before reassigning style.

### Rentals / economy

- `/market` should open polished Minecraft inventory GUI; `/market admin` staff GUI, with nested menus, breadcrumbs, pagination, navigation, confirmation screens, player search and editable values via chat/anvil where appropriate. **No web UI or Discord UI**.
- Player booth GUI: booth status, remaining term, extend, payments, auto-renew option if implemented, manage trusted players, kick trusted players, individual entry bans, access Public/Private/Locked, wall palette, visits/sales stats, view/teleport. Players owning multiple booths can choose one. `/market walls` opens wall palette, `/market recover` opens persisted multipage reclaim GUI, `/market browse` opens directory.
- Access modes: Public = all except banned; Private = owner+trusted; Locked = owner only. Admin bypass gated by `skyboxymarket.bypass`. Bans restrict **physical entry** (movement, teleport, passengers/vehicles where possible) including players already inside on changes. Resolve to safe exits, not suffocation.
- Fully in-game admin GUI: all booths by ID/owner, rent/extend/reassign, suspend, reset, end lease, price/duration, global and style-specific permissions for visitors, renters and trusted, style settings, template scan, diagnostics, logs, server stats, recovery review where privacy/permissions appropriate. Destructive actions must be confirmed.
- Economy via Vault: validate finite nonnegative amounts; debit once, durable lease write, commit/compensate on failure. Avoid double charging for GUI double-clicks and retry races. Handle missing economy provider as an explicit failure if prices >0. Consider crash consistency across SQLite and Vault; store payment idempotency keys and a repair procedure.
- Cooldowns are configurable per style (rent, extension/renewal as required, wall change). Permission requirement applies to **renting**, not retrospectively stripping existing renters unless admins decide.
- Long-lived lease expiration: warnings, configurable grace period, admin termination, immediate lock, safe reset, status indicator, database/audit.

### Walls

- The wall mask is the positions originally occupied by gray concrete in the style schematic; the white floor/structural shell stays immutable to players. `/market walls` and player GUI display all 16 vanilla concrete colors (or style subset). Recolor only mask positions, preserving other player-built concrete. Accept mixed concrete wall colors when scanning previously unregistered templates.
- Rotate wall mask correctly. Persist current wall color, charge/cooldown and audit. On lease end restore baseline template. If a mask block was missing, do not silently destroy other items; fail/repair with explicit admin warning.

### Holograms and teleport

- **Holograms in front of booths eventually**, but **center of matched schematic for now**. Separate configurable per-style/per-booth offset and rotation. Show vacant/rented status, tier, price and lease time; interaction opens rental / information GUI.
- TP uses **booth center for now** with safe-ground checks, configurable future front offset and yaw. Rotate offsets with booth orientation. A private/banned booth must reject unauthorized teleports. Don't teleport into solid blocks/void; handle world unloads/chunk changes.
- Holograms must not duplicate after chunk load, plugin reload, or server restart. Do not mistake hologram entities for player property on reset.

### Protection

- No individual WorldGuard regions. Plugin enforces visitor, renter, trusted and admin policy per computed booth cuboid; retain WorldGuard `marketmain` for high-level definition. Avoid WorldGuard flag conflicts, document recommended flags. Protect template structural mask against placement/break even by renters unless staff bypass; allow renter decor, storage, supported player shops.
- Listen to all meaningful alteration channels: placement/break, entity interaction/damage/frames/stands, containers/doors, fluids, pistons, explosions, hopper/dispensers/redstone, fire, grow/spread, armor stands, inventory moves, buckets, vehicles, teleports, command-based WorldEdit abuse where possible. Distinguish cosmetic breaks from recoverable placed blocks. Keep visitors able to purchase via supported shop plugin while not opening private inventories.
- GUI permission toggles must map to real enforced actions and work for members independently (inheritance allowed if clearly displayed).

### Item/block recovery — release blocking

- If rent expires, is canceled, or an admin deletes/ends a booth, **return all recoverable player-owned placed blocks, items in all containers, stocked shop items, recoverable equipped/frame items**, including full NBT/metadata (custom names, enchantments, shulker contents, custom items, PDC). Exclude permanent schematic shell/floor/walls from player ownership. Specify how modified inventories, hoppers, double-chests and dropped items are handled; do not accidentally capture items from outside plot or duplicate items from two chest halves.
- `/market recover` — dedicated 54-slot inventory GUI, pagination (45 payload slots + navigation), per-item click, claim page/claim-all with accurate remaining capacity; persist indefinitely by default, survive restart, support multiple previous leases.
- Critical: recovery must be **transactional, resumable and duplication resistant**. Design staged `LOCK -> SNAPSHOT -> SAVE INVENTORY JOURNAL -> SHOPS DETACH -> RESTORE -> FINALIZE`; use idempotent lease IDs and recovery entry keys. Never erase containers before DB recovery commit. After restart continue from durable state without recapturing already removed stock. Claim sequence must avoid loss/duplication on crash between DB claim and inventory award (e.g., durable claim intent + compensating recovery mechanism, or controlled inventory delivery protocol). Document limits honestly.
- Do not place a shop up for sale/rent while reset is incomplete. Do not reset concurrently with another reset or a new rental. Consider reset batches for lag, loaded chunk checks, WorldEdit failure and rollback snapshots.
- Admin booth deletion is *lease termination plus inventory recovery*; permanent deletion of a booth record must be separate, restricted and cannot silently destroy inventory.

### Integrations, stats, database

- SQLite persists styles or overrides as needed, booths, leases, trusted/banned UUIDs, wall color, access mode, visits, unique visitors, revenue (source-validated), cooldowns, safe recovery ledger and audit events. Track stable booth identities across rescans. Migration/versioned schema and integrity/backup commands are required for production.
- QuickShop-Hikari integration should be **verified against exact target build/APIs**, not assumed from reflection signatures. Honor shop ownership and stock; safely deregister before reset, protect purchase but not theft, track actual successful sales. If absent or incompatible, disable/restrict features cleanly without fake stats.
- Visitor stats: total/unique visit count; debounce re-entry; player-facing and admin stats via inventory GUI. Sales should only show proven provider events. Stats and other DB work should avoid blocking TPS.
- Explicit permissions: `skyboxymarket.use`, `.admin`, `.bypass`, per-style rent permission. Integrate PlaceholderAPI optionally only if desired; no dependency unless implemented.

## Current code inventory & critical review points

See `FEATURE_STATUS.md` for honest state. Codex should first investigate at minimum:

1. **Compile failures/API drift**: target `26.2.build.+`, WorldEdit 7.3.19 and WorldGuard 7.0.18; verify artifact availability and exact methods against current Paper. Install Gradle wrapper and pin reproducible versions.
2. **Snapshot of block state**: `MarketRecovery.snapshot` stores placed-block material, not full NBT and state. Validate capture of signs, banners, hanging entities, double chests, enchanted/custom inventory content, filters for original world blocks, shulkers, player-dropped items and other tile inventories.
3. **Reset crash consistency**: current recovery claim marks DB record before calling Bukkit inventory insertion and undoes on simple failure; a crash in between can cause item loss. Develop a crash-safe protocol. Existing reset phase transition and QuickShop removal are not proven transaction-safe.
4. **Scan budget**: `scan-blocks-per-tick` budgets candidate anchor *positions*, not total block lookups. A worst-case many near-matches can spike the server; benchmark and change to bounded reads/time.
5. **Template transform and matching**: current matching compares material but not directional block data. Template paste transform math, clipboard origin and orientation need physical tests in all four rotations. Native Schem blocks in supplied assets must be inspected.
6. **Loaded chunks**: current scan skips unloaded chunks silently except summary; implement safe rescan/status and avoid permanently omitting booths.
7. **Ownership/lease changes and refund**: current `rent()` mutates in-memory booth before `db.save()`; if save fails, it attempts to refund but may retain inconsistent state. Add transactional state and tests.
8. **WorldGuard coexistence**: event protections at HIGH priority may interact with WG checks. Validate event ordering/flag configuration and protect outer region.
9. **Permissions/access**: `MarketProtection` must not allow container theft via QuickShop integration or cross-boundary inventory moves, nor block legitimate purchases. Check menu authority *at action time* to prevent stale GUI clicks.
10. **Hologram entity lifecycle**: inspect duplicate handling and chunk load, `Interaction` click, uninstall cleanup; avoid interfering with recovery.
11. **Per-style features**: menus have editable config fields but this does not prove all fields, per-style permissions, or specific booth overrides work as designed; create feature-by-feature tests.
12. **Metrics**: `sales`/transaction information cannot be trusted until QuickShop events actually load; indicate provider-disabled state rather than `0` where unknown.
13. **Large server**: current `plugin.at` loops through all booths for every event. Use world+chunk spatial index and refresh it on insert/delete for scale.
14. **API access**: recovery logic `p.db` synchronous SQLite calls on tick and whole-entity enumeration can stall TPS; benchmark and improve carefully (Bukkit operations always on main thread).
15. **Security**: Validate user chat input, material names, schematic filenames (path traversal and extension), price bounds, offline UUID/name resolution, admin bypass, entity abuse, and style manipulations.

## Step-by-step implementation plan (execute in order)

### Phase 0 — reproducible build + requirements verification

- Install JDK 25, Gradle 9.x; add Gradle wrapper (`gradlew`, `gradlew.bat`, wrapper jar/properties); pin dependencies. Run `./gradlew clean test shadowJar` and fix Java/API issues. Record dependency versions and environment.
- Parse/compare the two supplied schematics independently; print dimensions, palette and bounding boxes in a checked-in test/report. Confirm white floor, gray wall mask and nonstandard blocks. Confirm template clipboard origins. Cross-check sample physical WorldEdit pastes in game.
- Write `docs/ARCHITECTURE.md` and keep feature checklist updated; implement plugin enable/disable smoke test with dependencies.

**Exit:** CI green and plugin enables/disables without exceptions on supported Paper; build reproducible.

### Phase 1 — template/multi-style discovery

- Finish rotated block-state matching and robust two-style distinction. Use geometry and template mask, not bedrock. Scan only marketmain, record durable booth ID and style; avoid duplicates and overlaps.
- Add scan progress, summary, cancellation, incremental loaded-chunk rescan and performance limits. Detect ambiguous templates and ask admin to resolve.
- Add `/market admin` > Styles and Scan GUIs for arbitrary styles and template file imports.

**Exit:** A small test world with rotated Normal and Corner structures identifies each correctly; stale/out-of-region and decorated existing booths are handled correctly; repeat scan adds zero duplicates.

### Phase 2 — secure rentals and navigation

- Implement price/permission/cooldown/lease checks in durable operations. Vault charge and logs. Renewal/max-duration/grace and admin extend/cancel/reassign with audit.
- Build complete in-game menus (player, admin, browse, detail, style CRUD, owner/player search), required confirmations and trusted/banned controls. Add public/private/locked entry enforcement and safe exits.
- Hologram center/default offset, click-to-open GUI, center/safe teleport and yaw rotation.

**Exit:** Rent, renew, expire and permissions/GUI actions work across restart without unauthorized entry or double charge.

### Phase 3 — protection + walls

- Restrict immutable schematic structure while allowing player items/decor. All 16 concrete colors; style-specific wall mask, cost and cooldown. Support discovery after color changes.
- Extend protection test suite to entities, containers, every relevant indirect block event, QuickShop purchases, teleport commands and vehicles; define WorldGuard flag contract.

**Exit:** All cross-role protection tests pass; no known outside-to-inside bypass or accidental player inventory disclosure.

### Phase 4 — recovery + crash safety (highest risk)

- Design recovery ledger and item serialization, double-chest unique capture, scheduled/batched resets, shop detach. Write automated tests for inventory snapshot/restore and restart after each reset phase; exercise full custom ItemMeta/PDC/shulker content.
- Implement `/market recover` pages, partial capacity claims, concurrent menu handling, safe claim journal, indefinite retention and audit.
- Admin termination triggers the same lossless recovery workflow as expiration; cleanup world only after ledger is durably committed.

**Exit:** Explicit restart-at-every-phase fault injection tests pass, and all recoverable test inventories/blocks are returned *exactly once*, without losing template structural blocks.

### Phase 5 — provider integration, stats and scalability

- Replace/refine QuickShop reflection with version-compatible integration tested against target jar. Confirm revenue only from successful confirmed sales; optional integration absence graceful.
- Add spatial indexing, measured scan budget, batched DB, robust migration/backups; unload/reload and restart soak testing.
- Add admin analytics (booths rented/vacant, income by tier, active leases, recovery queue size), player visit/sale stats, warnings and quality-of-life polish.

**Exit:** Performance budgets acceptable for 8+ premium plus many normal booths; QuickShop and no-QuickShop modes behave predictably.

### Phase 6 — release validation and handoff

- Test complete permissions matrix for Visitor/Renter/Trusted/Admin across PUBLIC/PRIVATE/LOCKED and lease states. Validate no players or item entities can grief, steal, bypass entry, or duplicate items.
- Test actual server restart during recovery, expiring rental in unloaded chunk, WorldGuard conflict, missing economy, missing schematic/style, database corruption and safe stop.
- Produce `CHANGELOG.md`, config reference, command/permission docs, migration/upgrading guide and rollback instructions; record tested server plugin versions.
- Upload signed/versioned release artifact from successful GitHub Actions run and provide exact matching source commit and SHA-256 checksums.

**Exit:** Green CI + signed-off Minecraft integration checklist + clean test server startup + recovery accounting. Only then advise installing on SkyBoxy production.

## Detailed acceptance / test matrix

| ID | Scenario | Expected |
|---|---|---|
| D01 | Normal schematic at N/E/S/W | Identified as normal once per booth |
| D02 | Corner schematic at N/E/S/W | Identified as premium once per booth |
| D03 | Color mixture in otherwise unclaimed wall | Still identified; no false premium |
| D04 | Entire booth crosses marketmain boundary | Not registered |
| D05 | Scan twice / server restart / booth later decorated | Existing booth ID, owner and style unchanged |
| D06 | Two templates match same cuboid | Flag ambiguous; never silently pick wrong price |
| D07 | Unloaded booth chunk during scan | Explicitly reported + re-scannable |
| R01 | No-rank player tries premium | Denied before charge |
| R02 | Repeat rent click or insufficient balance | At most one successful charge, no double ownership |
| R03 | Lease renewal / max duration / grace period | Correct persisted expiry and warnings |
| R04 | Admin deletes leased booth | Safe recovery, then booth reusable |
| A01 | Public / Private / Locked access + personal bans | Physical movement & teleport both enforced |
| A02 | Player already inside on ban/private change | Safely ejected and not suffocated |
| P01 | Visitor tries to break/place/open chest | Blocked with no side effect |
| P02 | Renter edits allowed interior | Works, is tracked, immutable shell safe |
| P03 | Trusted member permissions differ from renter | Correct enforcement |
| P04 | Hopper, pistons, explosions, fluids, entities, vehicles | No cross-boundary theft or mutation |
| W01 | All 16 colors on all orientations | Only intended wall mask recolored |
| W02 | Wall cost, cooldown and persistence | Charged once, state stored, future detect works |
| H01 | Chunk unload/reload and restart | Exactly one hologram per booth |
| H02 | Teleport center and per-style offset rotated | Safe and correct orientation |
| S01 | Unique visits and completed QuickShop sales | Accurate counters, never invented sales |
| F01 | Nested chest / custom NBT / enchanted / shulker stock | Items and metadata recovered exactly |
| F02 | Full inventory during claim | No loss; unclaimed stack remains in ledger |
| F03 | Crash before/after every reset stage | Idempotent reset and no duplicates |
| F04 | Concurrent recovery GUI clicks | Same recovery entry cannot be claimed twice |
| F05 | Missing world, schematic, dependency during reset | Booth locked; no deletion, admin actionable error |
| PERF01 | 1,000 registered booths | Acceptable tick cost for move/build/scan events |

## Operational documentation required

- How to prepare `marketmain` and WorldGuard flags, import `.schem`, configure world/style permissions/prices.
- Commands, permission nodes, all inventory menu paths, admin moderation flows and all error messages.
- WorldGuard/FAWE/Vault/QuickShop version compatibility matrix, what is optional, migration/rollback and data backup.
- Crash recovery steps and known limitations, including how to manually unlock a failed reset **without destroying a player's inventory**.
- Release notes stating where hologram and teleport defaults are (center), and how staff can later set per-style front offsets.

## Immediate first Codex prompt

> You are continuing a Paper plugin source snapshot for SkyBoxy Market. Read `README.md`, `CODEX_IMPLEMENTATION_PLAN.md`, `FEATURE_STATUS.md`, both `.schem` files, and all Java code. Do not claim it is finished. Start by installing Java 25/Gradle 9, adding a wrapper, running build/tests, fixing compile and API errors. Then implement each phase in the plan in order, prioritizing correct schematic detection and provably lossless recovery. All menus must be native Minecraft inventories. Generate booths only by scanning existing, administrator-built schematic structures inside `marketmain`; never place new booths automatically. Make incremental, tested commits; report test evidence and known gaps after each phase.

## Execution record — 2026-10-10

- Added a checksum-pinned Gradle 9.2.1 wrapper, pinned Java 25 dependencies, added CI wrapper usage, and built the shaded plugin successfully with `clean test shadowJar`.
- Added automated tests for both supplied schematic palettes/dimensions/masks, rotation and block-state matching, scan work limits, SQLite rental transactions, recovery migration/restart/deduplication, and role/access policy. All 18 tests pass.
- An isolated Paper 26.2 build 121 server enabled SkyBoxyMarket with FAWE, WorldGuard, and Vault. It loaded both bundled schematics; found Normal and Premium Corner structures in rotations 0–3; persisted eight IDs across a server restart; and repeated the scan without duplicate booths. The unloaded-chunk policy was also observed to skip and report an unloaded test region.
- Initial JAR before the continuation work (superseded): `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`; SHA-256 `90C13BC4A4A6AB1B64BF076E90DB8FE0FB90A0CA04E83C48BA9E7A8CF3CAC3F9`.
- Release gate remains open. Full player GUI/access/protection acceptance, a live Vault economy, QuickShop compatibility, supported-build FAWE reset restoration, full block-entity state capture, and Paper crash fault injection for every reset/claim boundary remain unverified or incomplete. Keep the artifact on an isolated test server.

## Execution record — 2026-10-10 continuation

- Closed the FAWE restoration uncertainty for the supplied assets on the scratch runtime. Both schematics restored after deliberate block damage at all four rotations with FAWE loaded; the implementation now uses the WorldEdit clipboard and Bukkit world adapter instead of the FAWE edit-session queue that failed on Paper 26.2 build 121.
- Added paired-chest 54-slot deduplication and a safe abort when the pair crosses the booth boundary. Placed container items preserve a `BlockStateMeta` shell while contents are journaled separately; the shulker shell is emptied to prevent a second copy of its contents. Death handling returns an uncommitted receipt to the ledger without leaving receipt-bearing death drops.
- Startup reset recovery now loads the booth chunks it needs, records failures per booth without skipping unrelated reset rows, and runs before hologram refresh. Paper restart acceptance unloaded pending booth chunks, then passed recovery from both `SNAPSHOTTING` and `RESTORING`.
- Added a world/chunk booth spatial index, SQLite integrity/reset/recovery diagnostics, and `/market backup` using `VACUUM INTO`. The backup is a database snapshot, not a full plugin/world backup.
- Added automated spatial-index and SQLite backup tests. Current Gradle run: 21 tests, 0 failures. Paper harness also passed both styles/four rotations, synthetic inventory/menu and focused protection checks, double-chest/shulker/item recovery, death receipt handling, and a test-only Vault API provider for rent, duplicate-rent, renewal, and wall charges.
- Final clean command `gradlew.bat clean test shadowJar` succeeded. JAR SHA-256: `A23062BBFAB544A9A500D62AB1ADE9858A37C223FD6DEEDFBE9B27386039A9A2`. Archive inspection confirmed both schematic assets are bundled byte-for-byte and the test harness is excluded.
- Current known phase state: Phase 0 build and targeted checks pass; Phase 1 scan works with its large-region/ambiguity gates open; Phases 2–5 remain partial as detailed in `FEATURE_STATUS.md`; Phase 6 release sign-off remains open. No human client, production economy provider, or QuickShop-Hikari JAR was available. Hard power-loss injection, comprehensive protection matrix, full tile-entity fidelity, style migrations/overrides, QuickShop sale/delete verification, and large-market TPS measurement remain incomplete.
- Keep the generated JAR on a disposable test server. Do not mark this plan complete or deploy to production until the remaining acceptance gates are closed.

## Execution record — 2026-10-10 payment and acceptance continuation

- Added schema migration 3 and durable `payment_intents`. Rental/renewal/wall commits transition to `APPLIED` in the same SQLite transaction as marketplace state. Interrupted `PREPARED`, `CHARGED`, and `REFUND_PENDING` intents become `REVIEW_REQUIRED`; the admin Payment Review GUI records only staff-verified outcomes and never repeats a charge/refund.
- Unit coverage injects a rental database failure, proves booth/lease rollback while preserving the charged intent, checks startup review and transitions, validates rent/renewal/wall atomic intent completion, and rejects invalid amounts. Current clean result: 25 JUnit tests, 0 failures, 0 errors, 0 skipped.
- Added multi-booth wall/teleport selection and admin owner search. Expanded Paper access tests for banned walk/teleport, private/trusted/locked entry and bypass, storage, fluid, piston and dispenser protection. Paper also passed all 16 concrete colors in each of four rotations without changing non-wall schematic blocks.
- A real `EssentialsX Economy` provider (`com.earth2me.essentials.economy.vault.VaultEconomyProvider`) was discovered on the scratch server; provider register/unregister, rent, renewal, and wall changes succeeded against a synthetic account. EssentialsX 2.22.0 warned that Paper 26.2 is unsupported. A separate test-only provider injected SQLite failure, successful compensation, refused compensation, and staff-verified manual refund.
- QuickShop-Hikari was absent. The bridge now validates required API/event signatures before becoming available and stays disabled when that validation fails. Shop deletion and sale accounting remain unverified against a target JAR.
- Expanded isolated Paper evidence includes repeated hologram refresh with one display/action pair across eight loaded booths, a 1,000-booth index benchmark (100,000 queries in 50–55 ms; 5,000 ms budget), and successful reset restart recovery from both `SNAPSHOTTING` and `RESTORING` after unloading the affected chunks.
- Exact clean-build artifact: `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`; SHA-256 `D2D831AA3DC89E90625CCE2F8F6A7C5442210BA6FD7DCD77CD344AB489B1C000`. Both schematic assets were compared byte-for-byte; the test harness is excluded. Paper acceptance logs: `.temp/paper-smoke/payment-fault-acceptance-1.log`, `.temp/paper-smoke/final-artifact-restart-1.log`, and `.temp/paper-smoke/final-artifact-restart-2.log`.

### Current phase state

- Build and packaging: verified for the clean Java 25 build.
- Schematic discovery/restoration and selected GUI/protection cases: verified in the scratch runtime; scanner boundary cases and complete role/action/WorldGuard coverage remain.
- Rentals/payments: durable intent and manual repair workflow implemented and tested. A crash inside a provider call is still inherently ambiguous and requires reviewing that provider's ledger; production provider behavior on a supported Paper build remains open.
- Item recovery/reset: metadata and receipt scenarios, clean restart tests, and one abrupt JVM halt after a flushed reset batch pass. Power loss, claim-boundary crashes, full tile-entity fidelity, and complete ownership cases remain open. Clipboard restoration is split into per-tick batches; the world flush, snapshot scan, chunk loading, and database calls remain synchronous.
- Optional shop integration: fail-closed behavior tested with QuickShop absent. Exact QuickShop-Hikari API, sale-event idempotency, and shop deletion remain unverified because no target JAR was supplied.
- Release gate remains open: no human player client, supported production runtime, power-loss and complete crash matrix, complete protection matrix, style-version/per-booth-override migration, or QuickShop target JAR was available/verified. Keep this artifact on an isolated test server.

## Execution record — 2026-10-11 resumable reset and final acceptance

- Added schema migration 4 with a durable, monotonic `restore_cursor`. Template restore now writes a configurable number of blocks per tick (`market.reset-blocks-per-tick`, default 256, clamped 1–4096), commits the cursor after each idempotent batch, and resumes from that cursor after restart. A crash before cursor commit replays only the last batch. Snapshot enumeration and chunk loading remain synchronous.
- Added database tests for entering `RESTORING`, monotonic cursor updates, and cursor persistence across SQLite restart. Final clean Java 25 `clean test shadowJar` passes 27 tests (0 failures/errors/skips).
- Expanded the Paper protection harness with hopper-boundary, explosion, and locked-vehicle cases. On the isolated Paper `26.2-121` runtime with FAWE `2.15.2+e9ed0d1`, all acceptance checks passed, including both styles at rotations 0–3, all wall colors, payment compensation/review, item receipts, and a 17-block-per-tick restore budget.
- Final-artifact restart acceptance passed from `SNAPSHOTTING` and from `RESTORING` with a partially written schematic at durable cursor 32. Affected chunks were unloaded before startup. These are clean restart tests, not abrupt power-loss tests.
- Final artifact: `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`; SHA-256 `F8EE544FE23DBC8EDEFE74DA0DE2778C7C0B0DEE2707D7CE23192319B9C4D41E`. The scratch server's plugin bytes matched this artifact, the Paper harness is excluded, and both supplied schematics were verified byte-for-byte.
- Paper logs: `.temp/paper-smoke/full-final-acceptance.log`, `.temp/paper-smoke/full-final-restart-snapshot.log`, and `.temp/paper-smoke/full-final-restart-restoring.log`.
- Release/implementation gates remain open: target QuickShop-Hikari JAR and supported production Paper/economy versions, real-player GUI and full WorldGuard/action checks, abrupt crash/power-loss claim/reset boundaries, unusual block-entity fidelity, style-version/per-booth overrides, and representative TPS measurement. EssentialsX `2.22.0` warned that the tested Paper build is unsupported. Do not label the plan complete or install this development snapshot on production.

## Execution record — 2026-10-11 world-flush ordering and abrupt restart

- Closed a reset durability gap: after each bounded schematic batch, `MarketRecovery` now calls `World.save(true)` before advancing the SQLite cursor. A cursor can no longer get ahead of the world's flushed chunk writes; if the database commit fails after the flush, the batch is replayed idempotently.
- A clean Java 25 `clean test shadowJar` build passed all 27 unit tests after this change. The isolated Paper 26.2/FAWE acceptance suite passed both schematics at four rotations, GUI/protection/economy/recovery checks, and the cursor-32 restart scenario.
- Added a process-termination fault test: the harness waits for the production restore task to advance beyond its staged cursor after a world flush, persists a verification marker, then calls `Runtime.halt(73)`. On the next startup, Paper completed the reset; the test verified the schematic, cleared lease, and exactly one recovery item with metadata. This covers one reset batch boundary, not operating-system power loss or every failure point.
- Latest isolated spatial-index check: 100,000 queries over 1,000 synthetic booths in 67 ms (5,000 ms smoke budget). A production-scale TPS check for the synchronous `World.save(true)` cost is still required.
- Final artifact: `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`; SHA-256 `824029A8D95505DE1A98A59D94265CF86F9EDAC80167EAB7108DFA617F3A3EC0`. Scratch server plugin matched the artifact; test harness excluded; both schematic resources retained byte-for-byte.
- Paper acceptance logs: `.temp/paper-smoke/durable-final-acceptance.out.log`, `.temp/paper-smoke/durable-final-restart-snapshot.out.log`, `.temp/paper-smoke/durable-final-restart-restoring.out.log`, `.temp/paper-smoke/durable-final-hard-crash.out.log`, and `.temp/paper-smoke/durable-final-post-hard-crash.out.log`.
- Remaining gates are unchanged in scope: QuickShop-Hikari exact-JAR compatibility, supported production runtime and economy, real-player GUI testing, full WorldGuard/action coverage, more reset and claim crash boundaries, power-loss behavior, unusual block-entity fidelity, style migrations/overrides, and production TPS.

## Execution record — 2026-10-11 partial claims and receipt ordering

- Reproduced three recovery bugs on Paper before fixing them: refusal when only part of a stack fit, lost pre-existing stock during multi-slot pending-receipt death, and unlocked saved tokens when reconnect reconciliation failed.
- Partial delivery now reads the authoritative owner-scoped item payload, computes matching-stack/empty-slot capacity, and atomically stores a pending portion plus an available remainder. Exact-payload comparison rejects stale attempts; the original lease/source/slot stays reserved for snapshot deduplication.
- Receipt quantity markers distinguish the new items from baseline stock in each merged stack. Death cleanup covers drops, retained inventory and selected-kept items, saves cleanup before reopening the pending portion, and retains a pending ledger after save failure. Reconnect locks saved tokens before attempting SQLite reconciliation.
- Added five SQLite tests for partial split/restart accounting, owner/stale requests, invalid payloads, remainder-insert rollback and available-payload scope. Final clean Java 25 build passes 32 tests (0 failures/errors/skips).
- Paper checks passed direct partial claims and native recovery item/page actions without importing GUI lore/tags into player stock. Injected finalization/reconnect/save failures passed; claim tests use simulated players and save callbacks, not real interrupted player files.
- The exact rebuilt JAR passed full isolated Paper/FAWE acceptance and the five-start sequence: `SNAPSHOTTING` recovery, cursor-32 `RESTORING` recovery, intentional `Runtime.halt(73)`, and post-halt recovery. The latest index sample was 100,000 queries over 1,000 booths in 33 ms.
- Latest artifact: `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`; SHA-256 `2DE05769D4F95F871F12777E242CA6CED4548728A482D7C62CEDC1630E7A1EB5`. Acceptance logs: `.temp/paper-smoke/partial-final-full-{1..5}.out.log` and `.temp/paper-smoke/partial-final-gui.out.log`; failure-to-success evidence: `partial-claims-red`, `partial-death-red`, `partial-claims-fault-red`, and `partial-claims-fault-green` logs in the same directory.
- Recovery remains incomplete against the full plan. Next coding work includes a separate whole-storage claim-all action; real player-file crash boundaries, broader ownership/metadata fidelity, and all previously recorded phase/provider/production gates remain open.

## Execution record — 2026-10-11 whole-storage claim-all (written up at the Claude handoff)

Codex implemented and accepted this work but stopped before recording it. This record was written by Claude from the code, `FEATURE_STATUS.md` and `RESUME_PLAN.md`. The `.temp/` logs exist only on the original machine and were not re-read.

- Added `MarketRecovery.claimAll` / `ClaimAllJob` and the `claimall` recovery-menu action, kept separate from "claim all on page". A pass fixes its upper bound at the highest available entry ID when it starts, then walks owner-scoped keyset pages. New resets and split remainders wait for the next pass.
- Each tick checks at most 16 entries and delivers at most one receipt (two player saves) per player. Entries that do not fit are skipped so later fitting stock still gets filled, and leftovers stay in recovery. Duplicate all-claim requests and concurrent item/page claims are refused.
- A pass stops with entries intact on disconnect, loss of market permission, plugin disable or a delivery error. The last fix added online checks on the menu, direct-delivery and all-pass routes, so a queued page action can no longer deliver to a disconnected player.
- A clean Java 25 build passes 35 tests (0 failures/errors/skips). Paper delivered 94 entries across three pages and passed the fault/cancellation checks listed in `docs/RECOVERY.md`.
- Tested artifact SHA-256: `72B260512F7149E2BA8CCE4BC9F982820849095151DB88729D2FA86B9AE8F696`. Acceptance: the `claim-all-offline-full-{1..5}` five-start sequence passed at 01:14 on that JAR, along with the earlier `claim-all-*` logs, all in `.temp/paper-smoke/` on the original machine.
- Claims still use simulated players. Real player-file crash boundaries and every earlier provider/production gate remain open.
