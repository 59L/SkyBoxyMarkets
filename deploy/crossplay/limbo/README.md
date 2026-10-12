# Login limbo

LibreLogin holds players here until they log in or are recognised as premium or
Bedrock. Nothing in this server is reachable by an unauthenticated player.

Use any lightweight limbo server (for example NanoLimbo or a void-world Paper)
with:

- port `25567`, bound to `127.0.0.1` (or firewalled to the proxy only);
- Velocity modern forwarding enabled with the same `forwarding.secret`;
- no plugins that store player data, no commands, no world saving.
