# Configuration reference

The plugin copies `src/main/resources/config.yml` to `plugins/SkyBoxyMarket/config.yml` on first startup. Configuration is YAML-backed; `/market reload` reloads styles and schematics. Back up the file before editing it through the admin GUI.

## Market settings

| Key | Default | Meaning |
|---|---|---|
| `market.world` | `world` | Loaded Bukkit world containing the marketplace. |
| `market.region` | `marketmain` | WorldGuard region scanned and used for common-area protection. |
| `market.scan-blocks-per-tick` | `1600` | Maximum scan positions processed per tick (scanner enforces a minimum of 100). |
| `market.scan-comparisons-per-tick` | `12000` | Maximum template cells compared per tick. |
| `market.scan-registrations-per-tick` | `20` | Maximum discovered booths committed per tick. |
| `market.scan-max-volume` | `15000000` | Maximum region volume accepted by a scan. |
| `market.scan-unloaded-policy` | `skip` | `skip` reports unloaded positions/candidates; `abort` cancels on the first required unloaded chunk. |
| `market.scan-verify-all-blocks-in-region` | `true` | Verify the full X/Z footprint at the bottom/top of the cuboid against the region. |
| `market.scan-match-air` | `true` | Require empty schematic cells to be empty during a structural match. |
| `market.auto-scan-on-start` | `false` | Start a bounded scan after plugin startup. |
| `market.deny-unregistered-area-edits` | `true` | Deny non-bypass edits and container opening in market commons/unregistered space. |
| `market.economy-enabled` | `true` | Present in the starter YAML but not currently used; paid actions depend on a registered Vault provider. |
| `market.max-total-booths-per-player` | `1` | Maximum active booths per player across all styles. |
| `market.visit-cooldown-seconds` | `30` | Per-player visit-stat debounce per booth. |
| `market.max-entrance-move-distance` | `8` | Present in the starter YAML but not currently used by safe-exit search. |
| `market.recover-unclaimed-items-days` | `0` | Present in the starter YAML but not currently used; entries currently remain indefinitely. |
| `market.expiry-check-ticks` | `1200` | Periodic lease expiration interval (scanner clamps to at least 200 ticks). |
| `market.reset-batch-size` | `256` | Maximum journal entries per SQLite batch (clamped to 1–2048). |
| `market.reset-blocks-per-tick` | `256` | Maximum schematic blocks restored per server tick (clamped to 1–4096); progress is journaled in SQLite and resumes after restart. |
| `market.allow-vanilla-container-sales` | `false` | Present in the starter YAML but not currently used; ordinary container access follows role policy. |

## Global permissions

`permissions.visitor`, `permissions.renter`, and `permissions.member` each accept `place`, `break`, `containers`, `doors`, `buttons`, `shop-interact`, `entities`, `redstone`, `fluids`, and `pvp`. The admin GUI can toggle these values. A matching `styles.<id>.permissions.<role>.<rule>` value overrides the global setting for that style. Unknown or absent rules default to denied.

The immutable schematic mask and unregistered market area remain protected independently of role toggles. `skyboxymarket.bypass` bypasses plugin protection and access checks.

## Shop plugins

QuickShop-Hikari 6.3.0.3, ChestShop 3.12.2 and mc-PlayerShop 1.5.1 are optional soft dependencies. They have no configuration keys; each provider enables itself when its plugin is installed and its expected API is present. Otherwise it reports `absent` or `incompatible` under `/market admin` > Providers, and does nothing. Booth sales read "unavailable" when no provider is available.

- Booth protection for clicks runs at `LOWEST` priority so it acts before shop plugins (ChestShop trades at `LOW`). `shop-interact` governs using a shop and `containers` governs opening its storage.
- Inside a booth, ChestShop shops can be created only by the renter or trusted members. Admin shops are always refused. mc-PlayerShop shops need a placed block, which follows the `place` rule. Creating a QuickShop shop in a booth as a visitor has not been tested.
- mc-PlayerShop menus are blocked when the booth denies `shop-interact`, because mc-PlayerShop opens them even for cancelled clicks.
- Sales count only money the owner receives, in Vault currency for mc-PlayerShop. mc-PlayerShop has no sale event, so its transaction records are read every 60 seconds.
- The `marketmain` WorldGuard region must not deny `build`, `interact`, `use` or `chest-access`; the plugin enforces renter rules itself. The acceptance fixture sets them to `allow`.

## Style settings

Each entry under `styles.<id>` may define:

- `name`, `schematic`, `icon`, `enabled`, `priority`.
- `rental.price`, `duration-hours`, `renewal-price`, `renewal-hours`, `grace-hours`, `cooldown-hours`, `max-per-player`, `max-duration-hours`, `required-permission`.
- `offsets.teleport` and `offsets.hologram`, each with `x`, `y`, `z`, and `yaw` (hologram yaw is not currently applied).
- `walls.template-material`, `cost`, `cooldown-seconds`, and `colors`.
- Optional role overrides under `permissions.visitor`, `permissions.renter`, and `permissions.member`.

Style IDs must be 2–32 lowercase letters/digits/underscores. Schematic names must be safe `.schem` filenames directly under `plugins/SkyBoxyMarket/schematics/`. Price values must be finite and nonnegative. Rental/renewal terms must be positive, grace/cooldown values nonnegative, `max-duration-hours` at least the initial duration, and allowed wall colors one or more of the 16 vanilla concrete colors. `walls.template-material` must be a concrete block (not concrete powder).

Editing a schematic for an active style is not supported as a migration. Keep existing style geometry stable; add a new style/version and use an explicit admin review before any reassignment.
