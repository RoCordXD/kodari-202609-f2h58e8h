package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

import java.util.List;

public class GameModeListener implements Listener {

    private final RoGuard plugin;

    // Aliases that count as "gamemode command"
    private static final String[] GAMEMODE_ALIASES = {
        "gamemode", "gm", "minecraft:gamemode",
        "gmc", "gms", "gma", "gmsp",
        "creative", "survival", "adventure", "spectator"
    };

    public GameModeListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        // Plugin-approved change (from verified command) -> allow
        if (plugin.consumeGameModeBypass(player.getUniqueId())) {
            return;
        }

        // Feature disabled -> allow
        if (!plugin.getConfigManager().isCommandVerificationEnabled()) {
            return;
        }

        // Gamemode command not restricted in command.yml -> allow
        if (!isGameModeRestricted()) {
            return;
        }

        // Player has bypass -> allow
        if (plugin.getConfigManager().isPlayerBypassed(player.getName())) {
            return;
        }

        // Not logged in yet -> let auth system handle it
        if (plugin.getConfigManager().isAuthEnabled()
                && !plugin.getAuthManager().isLoggedIn(player.getUniqueId())) {
            return;
        }

        // Block the change (covers F3+F4, /gm, plugins, etc.)
        event.setCancelled(true);

        String cmd = "/gamemode " + event.getNewGameMode().name().toLowerCase();
        player.sendMessage(plugin.getLanguageManager().msg("security.command-verify-required"));

        String id = plugin.getVerifyManager().addPendingCommand(player.getName(), cmd, false);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().sendVerifyRequest(id, player.getName(), cmd, false);
        }
    }

    /**
     * Check if any gamemode alias is listed in command.yml restricted-commands.
     */
    private boolean isGameModeRestricted() {
        List<String> restricted = plugin.getConfigManager().getCommandConfig()
                .getStringList("restricted-commands");

        for (String entry : restricted) {
            String e = entry.toLowerCase().trim();
            if (e.startsWith("/")) e = e.substring(1);
            if (e.contains(":")) e = e.substring(e.indexOf(":") + 1);

            for (String alias : GAMEMODE_ALIASES) {
                String a = alias.contains(":") ? alias.substring(alias.indexOf(":") + 1) : alias;
                if (e.equals(a)) return true;
            }
        }
        return false;
    }
}
