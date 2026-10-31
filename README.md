# SkyBoxyMarket

**Status: tested development build; not approved for a live marketplace.** The clean Java 25 build passes 35 automated tests. Isolated Paper checks with FAWE, WorldGuard, Vault, and EssentialsX cover both schematics at all four orientations, recovery snapshots, partial claims with durable remainders, whole-storage claims, receipt failures, resumable resets including one abrupt JVM halt, native inventory workflows, focused protection, and payment repair. The economy checks used a synthetic account; EssentialsX 2.22.0 warned that Paper 26.2 is unsupported. There was no human client or QuickShop-Hikari plugin. Keep this build on a disposable server until the remaining gates in [FEATURE_STATUS.md](FEATURE_STATUS.md) are closed.

## Build

Requirements: JDK 25 and the included Gradle 9.2.1 wrapper.

```powershell
.\gradlew.bat clean test shadowJar
```

The shaded plugin is `build/libs/SkyBoxyMarket-1.0.0-SNAPSHOT.jar`. SQLite JDBC is included in the JAR; Paper, WorldGuard, WorldEdit/FAWE, and Vault are server-provided. The checked-in CI workflow uses the wrapper. One JAR targets both Paper 26.1.2 and
26.2: production code always compiles against the pinned 26.1.2 API, and plugin
metadata declares 26.1.2 as its minimum. CI runs tests against both API versions.
To repeat the 26.2 API test locally, run
`./gradlew clean test shadowJar -PpaperTestApiVersion=26.2.build.133-stable`.
These API checks do not replace server acceptance with compatible dependencies.
Do not downgrade a 26.2 world or its serialized recovery items to 26.1.2.

Build classpath: Paper API `26.1.2.build.74-stable`, WorldEdit `7.4.0`, WorldGuard `7.0.18`, Vault API `1.7`, Java 25, Gradle 9.2.1. Runtime acceptance used Paper `26.2-121`, FAWE `2.15.2+e9ed0d1`, WorldGuard `7.0.17+2370-e42d8bc`, and Vault `1.7.3-b131`. See [FEATURE_STATUS.md](FEATURE_STATUS.md) for exact evidence and limits.

## Runtime requirements

- Paper 26.1.2 or 26.2 with Java 25. Use WorldGuard and WorldEdit/FAWE builds compatible with your server version.
- WorldGuard and Vault. A Vault economy provider is required when any configured charge is above zero; without one, paid actions fail closed.
- WorldEdit or FAWE for schematic loading and booth restoration. Clipboard restoration was tested on the isolated Paper build using Bukkit's WorldEdit world adapter; the FAWE edit-session queue is not used for paste because it failed on the tested build.
- QuickShop-Hikari is optional. The bridge now stays disabled unless the expected public API signatures are present. No target JAR was supplied, so sales figures and shop cleanup remain unverified and must not be treated as authoritative.

## First test-server setup

1. Back up the complete plugin data directory and use a disposable Minecraft world.
2. Install compatible Paper, WorldGuard, WorldEdit/FAWE, Vault, and (if desired) a Vault economy provider.
3. Create a WorldGuard region named `marketmain` in the configured `market.world`. It must include every booth block. Avoid WorldGuard build-deny settings that conflict with renter actions allowed by the plugin.
4. Confirm `mainMarket2.schem` and `marketboothCorner2.schem` are in `plugins/SkyBoxyMarket/schematics/`. Both are bundled and copied on first startup.
5. Set rental prices, durations, style permissions, wall colors, and offsets in `plugins/SkyBoxyMarket/config.yml`.
6. Load the region's chunks, then run `/market admin` → **Scan Schematics**. The scanner does not load chunks: skipped positions are reported. Load those chunks and use `/market scan resume` for a fresh pass.
7. On a disposable world, validate a rental, renewal, wall change, visitor/renter/member access, expiration, reset, and recovery before using a real market.

## Commands and permissions

| Command | Purpose |
|---|---|
| `/market` | Open the player marketplace menu |
| `/market browse` | Browse registered booths |
| `/market walls` | Open wall colors for the player's booth |
| `/market recover` | Claim items in recovery storage |
| `/market tp` | Teleport to an eligible booth |
| `/market admin` | Open staff tools |
| `/market scan [start\|status\|cancel\|resume]` | Scan, inspect, cancel, or repeat a scan (admin) |
| `/market reload` | Reload configuration (admin or console) |
| `/market backup` | Create a consistent SQLite database snapshot (admin or console) |

Permissions are `skyboxymarket.use`, `skyboxymarket.admin`, `skyboxymarket.bypass`, and each style's configured `required-permission` (the example premium node is `skyboxymarket.rent.premium`).

## Operational notes

- Booths are discovered from existing structures wholly inside `marketmain`; scanning never pastes or creates a booth.
- Scanner work is bounded per tick. The default unloaded-chunk policy is `skip`; change `market.scan-unloaded-policy` to `abort` to stop on an unloaded position/candidate. `resume` repeats a complete pass.
- The structural mask and WorldGuard region are protected even when a role's other permissions are allowed. Do not install individual booth WorldGuard regions.
- Reset snapshots are durable before schematic restoration starts. Each restore batch flushes the world before its SQLite cursor advances. Startup recovery loads the affected booth chunks itself; if the world or style schematic is missing or restore fails, the booth stays locked with its journal intact. Check server logs and diagnostics before manual intervention. This ordering has one isolated abrupt-JVM-halt test; power loss and every crash boundary are not certified.
- `/market backup` and the admin diagnostics screen are available. The backup is a database-only snapshot; stop the server and back up the full plugin data directory and world before upgrades or repair.
- Recovery claims return as many items as fit, including room in matching stacks. The claimed portion and any remainder are journaled together before inventory changes. Quantity-marked receipts preserve pre-existing stock on death and stay locked if ledger reconciliation fails. **Claim all storage** works through every page, at most 16 entries checked and one item delivered per tick, and leaves anything that does not fit in recovery. Do not manually delete recovery rows or edit the SQLite database.
- Recovery has separate item, page, and whole-storage buttons. The whole-storage pass checks all pages with bounded work, skips items that do not fit, and leaves new entries/remainders for the next pass. Disconnects, permission changes, or delivery failures stop safely; duplicate requests are blocked.
- Paid actions write a durable payment intent before calling Vault. If startup reports interrupted payments, open `/market admin` → **Payment Review** and compare each reference with the economy provider's transaction history. The buttons record a staff-verified outcome; they do not issue a refund or retry a charge.
- Before upgrading or rolling back, stop the server cleanly and back up the full plugin data directory, including the database, configuration, schematics, and any SQLite sidecar files.

## Documentation

- [Changelog](CHANGELOG.md)
- [Feature status and validation gaps](FEATURE_STATUS.md)
- [Architecture and state transitions](docs/ARCHITECTURE.md)
- [Schematic assets and scanning](docs/SCHEMATICS.md)
- [Recovery and reset behavior](docs/RECOVERY.md)
- [Configuration reference](docs/CONFIGURATION.md)
- [Operations, permissions, and upgrade steps](docs/OPERATIONS.md)
- [Compatibility and test matrix](docs/COMPATIBILITY.md)
- [Cross-play setup: cracked/premium, Bedrock, Java 1.21+](deploy/crossplay/README.md)
- [Implementation plan](CODEX_IMPLEMENTATION_PLAN.md)
