package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

import java.net.InetSocketAddress;

public class AntiVPNListener implements Listener {

    private final RoGuard plugin;

    public AntiVPNListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.getConfigManager().isAntiVPNEnabled()) return;

        Player player = event.getPlayer();
        String name = player.getName();

        if (plugin.getConfigManager().isVPNBypassed(name) || plugin.getConfigManager().isPlayerBypassed(name)) return;

        InetSocketAddress addr = event.getAddress() != null ? new InetSocketAddress(event.getAddress(), 0) : null;
        if (addr == null || addr.getAddress() == null) return;

        String ip = addr.getAddress().getHostAddress();

        plugin.getVPNChecker().checkIP(ip).thenAccept(result -> {
            if (result.isVPN()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(name);
                    if (p != null && p.isOnline()) {
                        p.kickPlayer(ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getVPNKickMessage()));
                    }
                });

                if (plugin.getSecurityBot() != null) {
                    plugin.getSecurityBot().logVPNDetection(name, ip, result.getReason());
                }

                plugin.getLogger().warning("[Anti-VPN] Kicked " + name + " - " + result.getReason());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isAntiVPNEnabled()) return;

        Player player = event.getPlayer();
        String name = player.getName();

        if (plugin.getConfigManager().isVPNBypassed(name) || plugin.getConfigManager().isPlayerBypassed(name)) return;

        InetSocketAddress addr = player.getAddress();
        if (addr == null) return;

        String ip = addr.getAddress().getHostAddress();

        plugin.getVPNChecker().checkIP(ip).thenAccept(result -> {
            if (result.isVPN()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.kickPlayer(ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getVPNKickMessage()));
                    }
                });

                if (plugin.getSecurityBot() != null) {
                    plugin.getSecurityBot().logVPNDetection(name, ip, result.getReason());
                }
            }
        });
    }
}
