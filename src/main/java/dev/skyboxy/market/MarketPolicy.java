package dev.skyboxy.market;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves per-style role overrides before falling back to the global marketplace policy. */
final class MarketPolicy {
    private MarketPolicy() {}

    static boolean allowed(ConfigurationSection config, String style, MarketRole role, String action) {
        String roleName = role.name().toLowerCase(Locale.ROOT);
        String override = "styles." + style + ".permissions." + roleName + "." + action;
        if (config.contains(override)) return config.getBoolean(override);
        return config.getBoolean("permissions." + roleName + "." + action, false);
    }

    static boolean canEnter(UUID player, UUID owner, Set<UUID> trusted, Set<UUID> banned,
                            String access, boolean suspended, boolean resetting, boolean bypass) {
        if (bypass) return true;
        if (resetting || suspended || banned.contains(player)) return false;
        if (Objects.equals(player, owner)) return true;
        return switch (access) {
            case "LOCKED" -> false;
            case "PRIVATE" -> trusted.contains(player);
            default -> true;
        };
    }
}
