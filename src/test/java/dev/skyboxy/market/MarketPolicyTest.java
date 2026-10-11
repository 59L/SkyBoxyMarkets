package dev.skyboxy.market;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarketPolicyTest {
    @Test
    void shouldUseGlobalRoleRuleUnlessStyleOverridesIt() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new StringReader("""
                permissions:
                  renter:
                    containers: true
                    place: true
                styles:
                  corner:
                    permissions:
                      renter:
                        containers: false
                """));

        assertTrue(MarketPolicy.allowed(config, "standard", MarketRole.RENTER, "containers"));
        assertFalse(MarketPolicy.allowed(config, "corner", MarketRole.RENTER, "containers"));
        assertTrue(MarketPolicy.allowed(config, "corner", MarketRole.RENTER, "place"));
        assertFalse(MarketPolicy.allowed(config, "standard", MarketRole.VISITOR, "pvp"));
    }

    @Test
    void shouldEnforcePublicPrivateAndLockedEntryRules() {
        UUID owner = UUID.randomUUID(), trusted = UUID.randomUUID(), visitor = UUID.randomUUID();
        Set<UUID> members = Set.of(trusted);

        assertTrue(MarketPolicy.canEnter(visitor, owner, members, Set.of(), "PUBLIC", false, false, false));
        assertTrue(MarketPolicy.canEnter(trusted, owner, members, Set.of(), "PRIVATE", false, false, false));
        assertFalse(MarketPolicy.canEnter(visitor, owner, members, Set.of(), "PRIVATE", false, false, false));
        assertTrue(MarketPolicy.canEnter(owner, owner, members, Set.of(), "LOCKED", false, false, false));
        assertFalse(MarketPolicy.canEnter(trusted, owner, members, Set.of(), "LOCKED", false, false, false));
    }

    @Test
    void shouldApplyBansSuspensionAndResetLockBeforePublicAccess() {
        UUID owner = UUID.randomUUID(), visitor = UUID.randomUUID();

        assertFalse(MarketPolicy.canEnter(visitor, owner, Set.of(), Set.of(visitor), "PUBLIC", false, false, false));
        assertFalse(MarketPolicy.canEnter(visitor, owner, Set.of(), Set.of(), "PUBLIC", true, false, false));
        assertFalse(MarketPolicy.canEnter(visitor, owner, Set.of(), Set.of(), "PUBLIC", false, true, false));
        assertTrue(MarketPolicy.canEnter(visitor, owner, Set.of(), Set.of(visitor), "LOCKED", true, true, true));
    }
}
