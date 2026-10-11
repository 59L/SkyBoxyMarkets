# Schematic assets and market scanning

The bundled assets are the agreed booth templates. They are not regenerated or replaced by the scanner.

| Style | File | Dimensions (X × Y × Z) | Schematic offset | Palette counts |
|---|---|---:|---:|---|
| Normal | `mainMarket2.schem` | 11 × 11 × 11 | `[-6, -3, 5]` | 810 air, 401 gray concrete, 90 white concrete, 11 fire coral blocks, 10 moss blocks, 9 nether wart blocks |
| Premium Corner | `marketboothCorner2.schem` | 9 × 11 × 8 | `[-5, -1, -7]` | 504 air, 231 gray concrete, 56 white concrete, 1 dead tube coral block |

The values are asserted by `StyleTemplateAssetTest` from the compressed Sponge NBT assets. The runtime loader also read both files in the isolated server smoke test. The configured `walls.template-material` positions form the recolorable mask; the matcher accepts any concrete at those positions, including a booth whose walls were recolored before its first scan. Other non-air template blocks, including the unusual coral, moss, and nether-wart blocks, remain fixed structural cells. Air cells are checked by default (`market.scan-match-air: true`); setting it false explicitly ignores air during structural matching.

## Rotation and box coordinates

Stored booth coordinates refer to the minimum corner of the rotated bounding box. X/Z template cells are transformed within that box:

| Rotation | Rotated X | Rotated Z | Bounds |
|---:|---|---|---|
| 0 | `x` | `z` | `width × length` |
| 1 | `length - 1 - z` | `x` | `length × width` |
| 2 | `width - 1 - x` | `length - 1 - z` | `width × length` |
| 3 | `z` | `width - 1 - x` | `length × width` |

Directional block data is rotated and compared with the world state. The asset tests cover rotation mapping and a directional stair/log/rail example. On Paper 26.2 build 121 with FAWE 2.15.2 loaded, the acceptance harness deliberately damaged one template cell and restored both supplied styles at rotations 0–3, then matched the resulting world state. `StyleTemplates` reads the WorldEdit clipboard and writes rotated block states through WorldEdit's Bukkit world adapter on the server thread. It avoids FAWE's edit-session queue because that queue raised `IllegalAccessError` on the tested Paper build. This verifies the plugin restore path for these assets and this runtime combination; it is synchronous and still needs lag measurement on production hardware.

## Scan procedure

1. Configure `market.world` and the WorldGuard region name at `market.region` (default `marketmain`).
2. Put administrator-built booths wholly inside the region. Do not rely on scanner-generated structures; the scanner only reads.
3. Load the chunks containing the booths and start `/market admin` → **Scan Schematics** or `/market scan start`.
4. Use `/market scan status`, `/market scan cancel`, or `/market scan resume`. `resume` performs another complete scan; it does not continue a saved cursor.
5. Review the final summary and the registered booth directory. Resolve any overlap or ambiguous-template report before opening rentals.

The default unloaded policy is `market.scan-unloaded-policy: skip`; skipped positions/candidates are reported. `abort` cancels when a required chunk is unloaded. No setting force-loads chunks. `market.scan-blocks-per-tick`, `market.scan-comparisons-per-tick`, and `market.scan-registrations-per-tick` bound the respective scanner work queues. Set `market.scan-max-volume` to a limit appropriate for the region. Keep `market.scan-verify-all-blocks-in-region: true` to ensure the entire footprint is inside `marketmain`.

The isolated scanner smoke used a 64 × 11 × 33 region, loaded its chunks, placed four of each bundled template, found eight booths, restarted the server, and repeated the scan without changing booth IDs or adding duplicates. A separate unloaded-region pass was explicitly reported as skipped. The later reset harness also confirmed restore after the booth chunk was unloaded. Large-region timing, boundary edge cases, heavily decorated booths, and full ambiguity scenarios still need acceptance tests.
