# SkyBoxyMarket — resume plan

Written 2026-10-11 as the handoff from the Codex sessions to Claude. Read this first, then `FEATURE_STATUS.md`. `CODEX_IMPLEMENTATION_PLAN.md` holds the authoritative product requirements and the full Codex execution record; nothing here overrides its requirements.

The plugin is a tested development build and is **not production-ready**. Do not install it on the live server (`PluginsNew`) without asking the owner first.

## 1. Where Codex stopped

Codex's last session implemented the whole-storage "claim all" recovery action and ran its acceptance, then stopped before writing it up or committing.

| Fact | State on 2026-10-11 |
|---|---|
| Unit tests | 35 pass, 0 failures/errors/skips (re-run on handoff with the local Java 25 toolchain) |
| Built JAR | `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`, SHA-256 `72B260512F7149E2BA8CCE4BC9F982820849095151DB88729D2FA86B9AE8F696` |
| Last Paper acceptance | `claim-all-offline-full-{1..5}` five-start sequence passed at 01:14, after the JAR was built at 01:11 |
| `FEATURE_STATUS.md` | Current: describes claim-all and the 35-test build |
| `CHANGELOG.md` | Current since Step 0 |
| `CODEX_IMPLEMENTATION_PLAN.md` | Current since Step 0: has a claim-all execution record |
| Git | Claim-all is in the remote's initial commit `b5a1f9c` |

Claim-all lives in `MarketRecovery.claimAll` / `ClaimAllJob` and the `claimall` action in `MarketMenus`. The last bug Codex fixed there was a queued page action delivering to a disconnected player; the fix added online checks on the menu, direct-delivery and all-pass routes.

Codex's own tracker for this work is `.temp/plan-mode/active/skyboxymarket-release/` (`plan.json`, `progress.json`). That directory is git-ignored, so it exists only on the original machine. This file replaces it as the tracked plan.

## 2. Local environment

Everything under `.temp/` is git-ignored and machine-local. On a fresh clone it must be rebuilt (section 2.3).

### 2.1 Build and unit tests

Run from the `SkyBoxyMarket` directory in PowerShell:

```powershell
$env:JAVA_HOME = "$PWD\.temp\toolchain\jdk-25.0.4.1+1"
$env:GRADLE_USER_HOME = "$PWD\.temp\gradle-home"
.\gradlew.bat clean test shadowJar --no-daemon
```

- Java 25 is required. A second JDK 25 is at `C:\Program Files\Java\jdk-25.0.2`; the system default `java` is 21 and will not build this.
- `clean ... shadowJar` rebuilds the JAR. Record the new SHA-256 in `FEATURE_STATUS.md` whenever the artifact changes; the hash above only describes the handoff build.
- `.\gradlew.bat test --rerun --offline --no-daemon` re-runs the tests without touching the JAR.

### 2.2 Isolated Paper acceptance server

The scratch server is `.temp/paper-smoke/` (Paper `26.2-121`, FAWE `2.15.2`, WorldGuard `7.0.17`, Vault `1.7.3`, EssentialsX `2.22.0`). It is separate from the live server.

- `PaperAcceptanceHarness` (in `src/test`) is packaged as the test-only plugin `SkyBoxyMarketSmoke.jar`. It is never bundled in the release JAR.
- `.temp/paper-smoke/run-claims.ps1 -LogPrefix <name>` copies the freshly built JAR and harness classes into the server and runs the claims-only suite.
- `.temp/paper-smoke/run-full-sequence.ps1 -LogPrefix <name>` runs the five-start sequence: full acceptance, `SNAPSHOTTING` restart, cursor-32 `RESTORING` restart, deliberate `Runtime.halt(73)`, post-halt recovery. It ends with `FULL SEQUENCE PASS`.
- Each start has a 45-second window; if a server is still alive after that, the scripts stop rather than start another. Check for a leftover Java process before re-running.
- Logs are `<prefix>-N.out.log` / `.err.log` in the same directory. Codex's convention is a `-red` log reproducing a bug before a `-green` log of the fix.

### 2.2a Claude machine (rebuilt 2026-10-11)

- JDK: `C:\Program Files\Java\jdk-25.0.2`. Paper `26.2-124` (Codex used 121). FAWE 2.15.2, WorldGuard 7.0.17 and EssentialsX 2.22.0 come from Modrinth; Vault 1.7.3 from its GitHub release. Downloads are in `.temp/downloads/`.
- One script, `.temp/paper-smoke/run.ps1 -Mode setup|claims|full -LogPrefix <name> [-Shops none|quickshop|chestshop|playershop|all|a,b] [-TimeoutSec n]`, replaces the two Codex scripts. It packages the harness as `SkyBoxyMarketSmoke.jar`, installs the selected shop JARs from the project root, and restores the 8-booth fixture from `.temp/paper-smoke-baseline/` before every claims/full run. `full` prints `FULL SEQUENCE PASS`.
- `-Mode setup` builds the fixture: `PaperAcceptanceHarness.setupAcceptanceWorld` pastes both styles at rotations 0–3 into a flat world inside the WorldGuard region `marketmain` (x -8..170, y -61..-45, z -8..70), and the production scanner registers them. This is test-only; the plugin itself never generates booths.

### 2.3 Rebuilding the environment on another machine

1. Install a JDK 25 and point `JAVA_HOME` at it.
2. Build with the checked-in Gradle wrapper (9.2.1).
3. Recreate a scratch Paper server with the plugin versions in 2.2 and package the harness; the run scripts are not tracked, so copy their logic from the description in 2.2.
4. Supply the three shop plugin JARs listed in section 3. They are deliberately not in git.

## 3. Shop plugin JARs supplied

All three sit in the project root and are ignored by `SkyBoxyMarket/.gitignore` (third-party binaries; do not commit them).

| Plugin | File | Version | SHA-256 |
|---|---|---|---|
| QuickShop-Hikari | `QuickShop-Hikari-6.3.0.3.jar` | 6.3.0.3 | `EB65B63D803B2C61BE6827F803572BA71F56BBAD9F1CD5959B9B746DF0669C3A` |
| ChestShop | `ChestShop.jar` | 3.12.2 (build 353) | `2465CC20437AEDFA74F33314A0ACED2FDB4F6B2B2154349D8BDC8DA85E499985` |
| mc-PlayerShop | `mc-PlayerShop-1.5.1.jar` | 1.5.1 | `26B020564A553AA909073B8B88F3512B7EB030796BB38726BC511CA0CCDB697C` |

`ChestShop.jar` is byte-identical to the one on the live server (`PluginsNew/ChestShop.jar`). Neither QuickShop-Hikari nor mc-PlayerShop is installed on the live server today.

### 3.1 What the JARs actually expose

These were read from the JARs with `javap`. None of it has been run on a server yet.

**QuickShop-Hikari 6.3.0.3**

- `QuickShopAPI.getInstance().getShopManager()` exists, with `getShopIncludeAttached(Location)` and `deleteShop(Shop)` (returns `void`).
- `ShopSuccessPurchaseEvent` has `getShop()`, `getAmount()`, `getBalance()`, `getBalanceWithoutTax()`, `getTax()`, `getPurchaser()`. **It has no `getTotal()`.**
- `QuickShopBridge` requires `getTotal()` at startup, so against this exact JAR the current bridge will disable itself. This is the first thing to fix.
- `Shop.getLocation()` is generic; use `Shop.bukkitLocation()` for a Bukkit `Location`.
- `ShopDeleteEvent` is a phased event (`Phase`), which is the confirmation signal for deletion.
- Stock lives in the physical container.

**ChestShop 3.12.2**

- Sign-plus-container shops with no shop registry; a shop exists while a valid sign is attached. Stock lives in the physical container.
- Bukkit events: `TransactionEvent` (cancellable; `getSign()`, `getExactPrice()`, `getTransactionType()`, `getOwner()`, `getClient()`), `PreShopCreationEvent` (cancellable), `ShopCreatedEvent`, `ShopDestroyedEvent`, `Protection.BuildPermissionEvent`, `Protection.ProtectionCheckEvent`.
- Static helpers: `ChestShopSign.isShopBlock(Block)`, `isShopChest(Block)`, `isValid(Sign)`, `isAdminShop(Sign)`; `uBlock.findConnectedShopSigns(Block)`, `findConnectedContainer(Sign)`.

**mc-PlayerShop 1.5.1**

- **No public API and no custom events.** Integration means reflecting on internals: `McPlayerShop.getInstance()`, `getShopManager()` (`get(Location)`, `getByChunk(Chunk)`, `unregister(Location)`), `getStorageManager()` (`getOrLoadSync(UUID)`, `remove(UUID)`), `getShopService()` (`remove(Player, PlayerShop)`, `buy`, `sell`).
- **Stock is stored in the plugin's own H2/MySQL database, not in the block.** Its default config says so, and shop blocks default to `BARREL`. A booth reset that only snapshots containers would miss this stock entirely.
- Transactions are a `PlayerShopTransaction` record (`shopId`, `playerUuid`, `type`, `amount`, `price`, `createdAt`) held in its database. There is no sale event to listen to.
- Supports several currencies (Vault, CoinsEngine, PlayerPoints) and has its own shop teleport (`/playershop` visit, `TeleportListener`).
- Downloads runtime libraries on first start (`zapper`), so the scratch server needs network access once.

## 4. Plan

Work in order. Each step ends with the clean build passing, the relevant Paper acceptance passing, `FEATURE_STATUS.md` updated honestly, and a commit.

### Step 0 — Close out Codex's claim-all session — DONE 2026-10-11

CHANGELOG, the CODEX execution record and README now describe claim-all. `docs/RECOVERY.md` already matched the code. Claim-all was already in the remote's initial commit. A clean rebuild on the Claude machine (JDK 25.0.2 at `C:\Program Files\Java\jdk-25.0.2`) passes 35 tests with hash `F29649E9…`; see `FEATURE_STATUS.md`.

- Add a `CHANGELOG.md` entry and a `CODEX_IMPLEMENTATION_PLAN.md` execution record for claim-all, citing the `claim-all-*` logs, 35 tests and the `72B26051…` artifact.
- Check that `docs/RECOVERY.md` and `README.md` describe claim-all the way the code behaves (16 entries per pass, one receipt per tick per player, leftovers stay in recovery).
- Commit the working tree as the baseline before any integration work.

**Done when:** the three status documents agree on test count, hash and claim-all state.

### Step 1 — Shop provider abstraction (no behavior change) — DONE 2026-10-11

`ShopProviders.java` holds the interface and the registry; `QuickShopBridge` implements it, and every call site has moved. Sale idempotency is durable: the `shop_sales` table, migration 5. Visible changes: diagnostics now show per-provider state, and sales read "unavailable" when no provider is available. 39 unit tests pass. The full five-start sequence passed with no shop plugin installed (`step1-noshop-{1..5}`). The `mc-PlayerShop` softdepend name matches that JAR's `plugin.yml`.

Replace the single `QuickShopBridge` field with a provider interface so three plugins can coexist.

- `ShopProvider`: `name()`, `available()`, `shopAt(Location)`, `detach(Booth)`, plus a sale callback into one shared `recordSale(booth, amount, key)`.
- `ShopProviders` registry holding zero or more providers. A block belongs to at most one provider; define and test the precedence.
- Call sites to move: `SkyBoxyMarket.java` (field and construction), `MarketRecovery.java` (`removeShops` before reset), `MarketProtection.java` (the `shop-interact` vs `containers` rule), `MarketMenus.java` (diagnostics provider line), and the harness fail-closed check.
- `plugin.yml` `softdepend` gains `ChestShop` and `mc-PlayerShop`.
- Diagnostics shows each provider as available, absent or incompatible, never a bare `0` for unknown sales.

**Done when:** unit tests with a fake provider cover precedence, detach failure keeping the booth locked, and sale idempotency; the existing absent-provider fail-closed Paper check still passes.

### Step 2 — QuickShop-Hikari 6.3.0.3 — DONE 2026-10-11

Results are in the `FEATURE_STATUS.md` QuickShop row. Owner revenue is `getBalance`, not `getBalanceWithoutTax`. Two bridge bugs were fixed: the listener ignored the event type (QuickShop events share a HandlerList), and the bridge counted buying-shop trades as revenue. Shop JARs come from Modrinth; QuickShop-Hikari and mc-PlayerShop match the section 3 hashes. `run.ps1 -Shops quickshop` pre-seeds a QuickShop config that charges tax to the owner.


- Replace the `getTotal()` requirement. Decide between `getBalance()` and `getBalanceWithoutTax()` by observing a real purchase; record which one is owner revenue.
- Use `bukkitLocation()`; confirm deletion through `ShopDeleteEvent` or the manager lookup rather than assuming `deleteShop` is synchronous.
- Confirm QuickShop starts on Paper 26.2 at all (its `api-version` is 1.20). If it does not, record that and stop this step.
- Paper acceptance: shop created in a booth by the renter; visitor purchase allowed while the chest stays unopenable; sale counted exactly once; reset deletes the shop and returns stock exactly once; QuickShop display items are not captured as recoverable items.

**Done when:** the QuickShop row in `FEATURE_STATUS.md` can cite real runs against this exact JAR.

### Step 3 — ChestShop 3.12.2

This is the provider already on the live server, so it matters most for production.

- `shopAt` via `ChestShopSign.isShopBlock` / `isShopChest`.
- Creation rules via `PreShopCreationEvent` and `BuildPermissionEvent`: only the renter and permitted trusted members create shops inside a booth; decide whether admin shops are allowed in booths (default: no).
- Sales via `TransactionEvent` at `MONITOR`, ignoring cancelled events. Establish from a real run which `TransactionType` is owner revenue and count only that.
- Detach on reset: remove shop signs inside the booth cuboid before snapshot so stock is journaled once from the container, and no orphan sign survives the restore.
- Check ChestShop's own WorldGuard soft-dependency does not fight `MarketProtection` event ordering.

**Done when:** the same acceptance cases as Step 2 pass for ChestShop, including a double-chest shop.

### Step 4 — mc-PlayerShop 1.5.1

Highest risk of the three, because stock is outside the world and there is no API.

- Pin to exactly 1.5.1: verify every reflected class and method at startup and fail closed on any mismatch, as the QuickShop bridge does.
- Recovery: before reset, for each shop in the booth, journal the database-held stock into the recovery ledger, then remove the shop, then confirm it is gone. Key the journal by shop ID so a restart cannot journal the same stock twice. Work out the safe removal path first; `PlayerShopService.remove` takes a `Player` and may hand items to them, which would duplicate journaled stock.
- A shop that cannot be drained or removed leaves the booth locked with belongings journaled, never reset.
- Sales: there is no event. Investigate reading its transaction records; if that is not reliable, show sales as unavailable for this provider rather than inventing numbers. Count only the Vault currency toward booth revenue.
- Its shop teleport must respect booth access modes and bans; test that `MarketProtection`'s teleport enforcement catches it.
- Fault tests: crash between journal and removal, and between removal and reset, each returning stock exactly once.

**Done when:** stock held in mc-PlayerShop's database is returned exactly once through `/market recover` across a restart, or the provider is explicitly reported as unsupported with the reason.

### Step 5 — Multi-provider acceptance and documentation

- Run the scratch server with all three installed, and with each one alone, and with none.
- Extend the protection matrix with the shop cases: visitor buys but cannot open storage, for every provider and every access mode.
- Update `docs/COMPATIBILITY.md` (exact versions and hashes), `docs/CONFIGURATION.md`, `docs/RECOVERY.md` (what each provider's reset does to stock) and `FEATURE_STATUS.md`.

### Step 6 — Release gates carried over from Codex

Unchanged from `FEATURE_STATUS.md`; none were closed by the shop work.

1. Item recovery (release-blocking): real player-file crash boundaries, power loss, unusual block entities, full sign and tile-state fidelity.
2. Protection (release-blocking): full role/action matrix, production WorldGuard event ordering, redstone.
3. Reset: every crash boundary, missing world or schematic, and TPS cost of the synchronous `World.save(true)`.
4. Scanner: ambiguous and boundary cases, large-region scan timing.
5. Menus: style-version migrations, per-booth overrides.
6. Holograms: real chunk unload/reload.
7. A supported Paper and economy combination (EssentialsX 2.22.0 warns on Paper 26.2).
8. Real-player GUI checks. These need a human client and cannot be closed by the harness.

### Step 7 — Release handoff

Clean build, full five-start sequence, final SHA-256, documentation matching behavior. Only then propose installing on the live server, and ask first.

## 5. Decisions needed from the owner

Answered by the owner on 2026-10-11: support and test all three providers equally; no admin shops inside rented booths; "sales unavailable" is acceptable for mc-PlayerShop if its records cannot be read reliably.

1. Which shop plugin will the market actually use in production? ChestShop is live today; the other two are not installed. Supporting all three is planned, but the answer sets test priority.
2. Should admin shops be allowed inside rented booths?
3. If mc-PlayerShop sales cannot be read reliably, is "sales unavailable" acceptable for that provider?

## 6. Ground rules

- Keep edits inside `SkyBoxyMarket`; the parent repository holds unrelated sibling plugins.
- Never auto-generate or paste booths during discovery, and never create per-booth WorldGuard regions.
- Never erase containers or shop stock before the recovery ledger commit is durable.
- Optional integrations fail closed: an absent or incompatible shop plugin disables that provider and never invents sales.
- Report only what was run. A feature is "verified" only for the scenarios listed beside it.
