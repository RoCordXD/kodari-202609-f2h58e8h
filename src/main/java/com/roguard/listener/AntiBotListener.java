package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class AntiBotListener implements Listener {

    private final RoGuard plugin;

    public AntiBotListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.getConfigManager().isAntiBotEnabled()) return;

        String name = event.getPlayer().getName();
        if (plugin.getConfigManager().isAntiBotBypassed(name)) return;
        if (plugin.getConfigManager().isPlayerBypassed(name)) return;

        String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;

        if (plugin.getAntiBotManager().isBot(name, ip)) {
            String reason = plugin.getAntiBotManager().detectReason(name, ip);
            String msg = plugin.getConfigManager().getAntiBotKickMessage();

            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));

            plugin.getLogger().warning("[AntiBot] Blocked " + name + " - " + reason);

            if (plugin.getSecurityBot() != null) {
                plugin.getSecurityBot().logAntiBot(name, ip, reason);
            }
        }
    }
}
