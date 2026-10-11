# Operations and configuration

## Dependencies and compatibility

Compile dependencies are pinned to Paper API `26.2.build.133-stable`, WorldEdit `7.4.0`, WorldGuard `7.0.18`, and Vault API `1.7`; build with Java 25 and the included Gradle 9.2.1 wrapper. WorldEdit/FAWE, WorldGuard, and Vault are provided by the server and are not bundled in the plugin JAR. SQLite JDBC is shaded into the plugin.

The isolated acceptance server used Paper `26.2-121`, FAWE `2.15.2+e9ed0d1`, WorldGuard `7.0.17+2370-e42d8bc`, Vault `1.7.3-b131`, and EssentialsX `2.22.0`. EssentialsX's real Vault provider completed rent, renewal, and wall charges against a synthetic test account, but warned that this Paper version is unsupported. A test-only provider exercised database failure and compensation. QuickShop and a human player client were not present. Match production versions and complete the release gates before deployment.

## Commands and permissions

| Command | Permission | Notes |
|---|---|---|
| `/market` | `skyboxymarket.use` or `.admin` | Opens the main inventory menu. |
| `/market browse` | same | Opens the booth directory. |
| `/market walls` | same | Opens the renter's wall palette. |
| `/market recover` | same | Opens recovery storage. |
| `/market tp` | same | Teleports to the player's booth if access allows it. |
| `/market admin` | `skyboxymarket.admin` | Opens admin inventory workflows. |
| `/market scan start\|status\|cancel\|resume` | `skyboxymarket.admin` | Scans the configured WorldGuard market region. |
| `/market reload` | `skyboxymarket.admin` (console allowed) | Reloads config and styles. |
| `/market backup` | `skyboxymarket.admin` (console allowed) | Writes a timestamped consistent SQLite snapshot under the plugin's `backups` directory. |

`skyboxymarket.bypass` is the protection/access bypass. Style `rental.required-permission` applies to new rentals. The bundled premium example uses `skyboxymarket.rent.premium`.

## Configuration reference

The source default is `src/main/resources/config.yml`; the generated runtime file is `plugins/SkyBoxyMarket/config.yml`.

| Path | Purpose |
|---|---|
| `market.world`, `market.region` | World and WorldGuard cuboid name (`marketmain` by default). |
| `market.scan-blocks-per-tick` | Maximum region positions inspected per tick. |
| `market.scan-comparisons-per-tick` | Maximum template cells compared per tick. |
| `market.scan-registrations-per-tick` | Maximum new booth rows registered per tick. |
| `market.scan-max-volume` | Safety cap on region volume. |
| `market.scan-unloaded-policy` | `skip` (report and continue) or `abort` when required chunks are unloaded. |
| `market.scan-verify-all-blocks-in-region` | Validate the complete template footprint against `marketmain`. |
| `market.scan-match-air` | Whether air cells must match the schematic. Defaults to strict (`true`). |
| `market.deny-unregistered-area-edits` | Whether edits in commons/unregistered market area are denied. |
| `market.max-total-booths-per-player` | Global active booth limit per player. |
| `market.visit-cooldown-seconds` | Minimum interval between persisted visits by the same player. |
| `market.reset-batch-size` | Maximum recovery ledger entries committed per SQLite transaction. |
| `permissions.<visitor|renter|member>.<rule>` | Global role policy for place, break, containers, doors, buttons, shop-interact, entities, redstone, fluids, and pvp. |
| `styles.<id>.enabled`, `priority`, `icon`, `name`, `schematic` | Style visibility, match priority, menu item, display name, and safe `.schem` filename. |
| `styles.<id>.rental.*` | Price, lease duration, renewal, grace, cooldown, player limits, prepaid cap, and required permission. |
| `styles.<id>.walls.*` | Template material, allowed concrete colors, cost, and cooldown. |
| `styles.<id>.offsets.teleport`, `.offsets.hologram` | Style-relative offsets and yaw. Defaults target booth centers. |
| `styles.<id>.permissions.<role>.<rule>` | Optional role rules overriding the global values. |

Prices must be finite and nonnegative, durations valid, schematic paths plain filenames, and the wall template material concrete. Keep a copy of the old config before GUI edits or reloads. Runtime style configuration is currently YAML-backed; complete style-version migrations are not implemented.

## WorldGuard setup

Create a cuboid region named by `market.region` in `market.world`, covering all booth blocks and common market paths. Scanning only registers structures wholly within this region. Do not create one WorldGuard region per booth. Configure region flags so renter permissions intended by the plugin are not overridden by a broad WorldGuard build-deny rule. Plugin protection also denies edits in unregistered market space when `market.deny-unregistered-area-edits` is true.

## Interrupted payment review

Each paid action creates a SQLite payment intent before Vault is called. Successful rent, renewal, and wall updates mark the intent `APPLIED` in the same SQLite transaction as the marketplace change. Provider refusals, database errors, and ambiguous provider exceptions retain an intent instead of silently discarding the payment state.

On startup, any intent left in `PREPARED`, `CHARGED`, or `REFUND_PENDING` becomes `REVIEW_REQUIRED`. Open `/market admin` → **Payment Review**, then compare the reference, player, booth, amount, provider, and recorded state with the provider's transaction history and the booth state. Do not ask the player to retry while the result is unclear.

The review buttons only record a staff-verified outcome: **No debit confirmed**, **Refund confirmed**, or **Marketplace action confirmed**. They do not send money or repeat an economy request. If a refund is owed, issue it through the economy provider's supported staff workflow, verify it in provider history, and then record **Refund confirmed**. Keep the provider reference in your staff records. The plugin cannot make Vault and SQLite one atomic transaction or safely repeat a refund after an ambiguous provider response.

## Backup, upgrade, and rollback

1. Stop the server cleanly. `/market backup` may also create a convenient database-only SQLite snapshot while it is running.
2. Copy the entire `plugins/SkyBoxyMarket` directory. Keep the config, both schematic files, SQLite database and any SQLite `-wal`/`-shm` sidecar files together. The plugin command does not back up config, schematics, worlds, or server/plugin files.
3. Install the new JAR and start on a staging server first. Review startup warnings and interrupted reset records.
4. If rollback is required, stop the server, restore the previous JAR and the matching complete plugin data-directory backup, then restart.

The plugin adds SQLite columns and migration markers automatically for known earlier schemas. `/market backup` creates a database-only snapshot; there is no in-plugin restore command or general downgrade mechanism. Never restore an old database without also restoring the corresponding recovery/reset rows and world backup.

## WorldGuard / economy / shop behavior

- When Vault or its economy provider is missing, nonzero charges are refused. Payment intents record database/compensation failures for review; they cannot make external Vault movements atomic with SQLite.
- QuickShop-Hikari is optional. The bridge stays disabled if its expected public API signatures are absent. No exact target JAR was available, so tracked sales and shop removal remain unverified; do not rely on sales totals or enable resets that depend on QuickShop cleanup until tested on a copy.
- Check scan and reset results in server logs. Never force-reset a booth after an error without first preserving the recovery and reset records.
