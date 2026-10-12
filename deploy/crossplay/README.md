# Cross-play setup: cracked + premium, Java + Bedrock, Java 1.21+

**Status: configuration kit, not yet run.** None of this has been started on a server
or joined with a real client. Treat every item in [Acceptance](#acceptance) as open
until it has been run, and do not apply it to the live server (`PluginsNew`) without
the owner's go-ahead.

## What players get

| Who | How they join | Login | UUID they play under |
|---|---|---|---|
| Premium Java (Mojang account) | `play.example:25565` | Automatic, verified with Mojang | Their real Mojang UUID |
| Cracked Java (any name) | `play.example:25565` | `/register` once, then `/login` | Offline UUID derived from the name |
| Bedrock (phone, console, Win10) | `play.example:19132` | Automatic, verified with Xbox Live | Floodgate UUID, name shown as `.Gamertag` |

Java clients from **1.21 up to the server's own version** can join. The request said
"21.11", which reads as 1.21.11, the last 1.21 release; the floor is set to 1.21.0 so
every 1.21.x client works, because that is what players commonly run. To raise or
lower it, change one line in `paper/viaversion-config.yml.snippet`. Going below 1.21 is
possible with ViaBackwards but each step back loses more item and menu fidelity, which
matters for the market's menus.

## Layout

```
                 Java 1.21+ :25565 ──┐
                                      ├─ Velocity proxy ── LibreLogin ─┬─ limbo  127.0.0.1:25567 (until logged in)
  Bedrock :19132 (UDP) ─ Geyser ──────┘   + Floodgate                    └─ skyboxy 127.0.0.1:25566 (Paper + ViaVersion/ViaBackwards
                                                                                                     + SkyBoxyMarket and the rest)
```

| Piece | Runs on | Job |
|---|---|---|
| [Velocity](https://papermc.io/software/velocity) | proxy | The only public Java port. Runs in offline mode so cracked clients can connect. |
| [LibreLogin](https://github.com/kyngs/LibreLogin) | proxy | `/register` and `/login` for cracked players, Mojang-verified auto-login for premium names, skips Bedrock players. Holds everyone in `limbo` until authenticated. |
| [Geyser](https://geysermc.org) | proxy | Translates Bedrock to Java. |
| [Floodgate](https://geysermc.org/wiki/floodgate/) | proxy (and optionally backend) | Lets Bedrock players in without a Java account; prefixes their names with `.`. |
| [ViaVersion](https://modrinth.com/plugin/viaversion) + [ViaBackwards](https://modrinth.com/plugin/viabackwards) | SkyBoxy backend | Lets older Java clients (and Geyser, if it targets an older Java version than the server) talk to the server. |
| Limbo server | loopback | Empty holding server for unauthenticated players. See `limbo/README.md`. |

Why a proxy instead of plugins on one Paper server: an auth plugin on the game server
lets an unauthenticated player into the real world before login, and every plugin that
reacts to joins sees them. With LibreLogin on Velocity, nobody reaches the SkyBoxy server
until they are authenticated, and the backend's own UUIDs come only from the signed
forwarding data.

The usual alternative, FastLogin + AuthMe, also works but needs plugins on both the
proxy and every backend. Use it only if LibreLogin has no build for the Velocity version
you run.

## Install order

1. **Back up first.** Stop the live server and copy the whole server directory, every world,
   and `plugins/` (including `SkyBoxyMarket/` and its SQLite files, Essentials `userdata/`,
   WorldGuard regions).
2. **Check how existing players are keyed** (see [UUIDs](#uuids-read-this-before-switching)).
3. Download current builds of Velocity, Geyser-Velocity, Floodgate-Velocity, LibreLogin,
   ViaVersion and ViaBackwards. Each must state support for the server's Minecraft version
   (Paper 26.2) and the Velocity build you use. Record the file name and SHA-256 of each.
4. Start Velocity once to generate `velocity.toml` and `forwarding.secret`, then merge
   `velocity/velocity.toml.snippet`.
5. Put Geyser, Floodgate and LibreLogin in Velocity's `plugins/`, start once, stop, then set
   the values in [Plugin settings](#plugin-settings).
6. On the SkyBoxy backend, merge `paper/server.properties.snippet`, `paper/spigot.yml.snippet`
   and `paper/paper-global.yml.snippet` (paste the forwarding secret). Install ViaVersion and
   ViaBackwards and merge `paper/viaversion-config.yml.snippet`.
7. Set up the limbo server (`limbo/README.md`).
8. Firewall: open TCP 25565 and **UDP** 19132 to the internet. Close 25566 and 25567 to
   everything except the proxy.
9. Start limbo, then SkyBoxy, then Velocity, and run [Acceptance](#acceptance).

## Plugin settings

The setting names below are from each plugin's generated config. Plugins rename keys
between releases, so set them in the file the plugin generates rather than pasting a
whole file.

### Geyser (`plugins/Geyser-Velocity/config.yml`)

| Setting | Value | Why |
|---|---|---|
| Bedrock port | `19132` | Default Bedrock port; UDP. |
| Remote address | `auto` | Uses Velocity's own listener. |
| Auth type | `floodgate` | Bedrock players don't need a Java account. |
| Pass through MOTD / player counts | `true` | Bedrock server list shows the same info as Java. |

### Floodgate (`plugins/floodgate/config.yml`)

| Setting | Value | Why |
|---|---|---|
| `username-prefix` | `"."` | Keeps Bedrock gamertags from colliding with Java names. Never set it empty. |
| `replace-spaces` | `true` | Gamertags may contain spaces; Java names cannot. |
| `player-link.enabled` | `true` | Lets a player link their Bedrock and Java accounts so both share one UUID. |

If any backend plugin uses the Floodgate API, also install Floodgate on that backend and
copy the proxy's `key.pem` to it. `key.pem` is a secret: anyone holding it can log in as
any Bedrock player. Never commit it.

### LibreLogin (`plugins/librelogin/config.conf`)

| Setting | Value | Why |
|---|---|---|
| `limbo` | `["limbo"]` | Where unauthenticated players wait. |
| `lobby` root | `["skyboxy"]` | Where they go after login. |
| `new-uuid-creator` | `MOJANG` | Premium names keep their real Mojang UUID; others get offline UUIDs. See [UUIDs](#uuids-read-this-before-switching). |
| `auto-register` | `true` | A name that belongs to a Mojang account is reserved for that account: a cracked client using it is refused instead of taking over the owner's data. |
| Minimum password length | `8` or more | |
| Database | its default SQLite, or the network's MySQL/MariaDB | Back it up with the server. |

Staff accounts (op, `skyboxymarket.admin`, `skyboxymarket.bypass`) should be premium
accounts, and should turn on LibreLogin's TOTP 2FA. A cracked admin account is protected
by a password only.

## UUIDs: read this before switching

Every plugin stores players by UUID: SkyBoxyMarket booth owners, members, bans and
recovery storage; Essentials balances; WorldGuard members; player inventories. Changing
how UUIDs are assigned orphans that data silently.

- **Live server is online-mode today:** everyone so far is premium with a Mojang UUID.
  `new-uuid-creator = MOJANG` keeps those UUIDs, so existing data keeps working. Nothing to
  migrate.
- **Live server is already offline-mode (or behind a cracked proxy) today:** players are
  stored under offline UUIDs. Setting `MOJANG` would move premium players to new UUIDs and
  hide their booths, money and items. Use `CRACKED` instead to keep everyone on offline
  UUIDs, or plan a deliberate migration with a full backup.
- Check which applies by looking at `usercache.json` or `world/playerdata/`: Mojang UUIDs
  are version 4 (third group starts with `4`), offline ones are version 3 (starts with `3`).
- Bedrock players get UUIDs of the form `00000000-0000-0000-xxxx-xxxxxxxxxxxx` unless they
  link to a Java account.

## Security checklist

- Backend ports (25566, 25567) are not reachable from the internet. With offline mode, an
  exposed backend is the most common way servers get taken over.
- Paper uses Velocity **modern** forwarding with the secret; `spigot.yml` `bungeecord` is
  `false`.
- `forwarding.secret`, Floodgate `key.pem` and the LibreLogin database are not in git.
  This repository's `.gitignore` excludes them under `deploy/`.
- Floodgate keeps a non-empty `username-prefix`.
- LibreLogin `auto-register = true` so premium names cannot be claimed by cracked clients.
- No plugin trusts a player name as identity; use UUIDs. (SkyBoxyMarket's player lookup by
  name reads only Paper's local cache, so it returns the UUID that player actually joined
  with.)

## SkyBoxyMarket on Bedrock and older clients

- Menus are chest inventories driven by plain clicks; they don't rely on shift, number-key
  or right-click actions, so they work through Geyser's inventory translation.
- Typed prompts (player names, prices) use chat, which Bedrock supports. Bedrock players
  are typed with their prefix, for example `.Steve`.
- Booth holograms are `TextDisplay` entities. Geyser's support for display entities is
  partial; on Bedrock the label may be missing or misplaced. The booth itself and its
  menus work without it. This is an acceptance item.

## Acceptance

Run each with a real client and record the result and plugin versions in
`docs/COMPATIBILITY.md`.

- [ ] Premium Java account joins, is auto-logged-in, and `/market` shows the booths it owned
      before the switch (same UUID).
- [ ] Cracked Java client with a new name registers, logs in, rents a booth, leaves, rejoins.
- [ ] Cracked client using an existing premium name is refused.
- [ ] Unauthenticated player in limbo cannot reach `skyboxy` or run commands.
- [ ] Bedrock player joins on 19132, appears as `.Gamertag`, opens `/market`, browses, rents,
      opens a booth's menus, uses `/market recover`.
- [ ] Java 1.21.0 (or the oldest allowed), 1.21.11, and the current version all join and use
      the market menus; a 1.20.x client gets the block message.
- [ ] A Java owner adds a Bedrock player (`.Name`) as a booth member by name, and vice versa.
- [ ] Connecting directly to 25566 from outside the host fails.
