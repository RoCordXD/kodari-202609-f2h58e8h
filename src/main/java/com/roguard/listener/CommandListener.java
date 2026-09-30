package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandListener implements Listener {

    private final RoGuard plugin;

    public CommandListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // Commands that contain sensitive data (passwords)
    private static final String[] SENSITIVE_COMMANDS = {
        "login", "l", "register", "reg", "changepassword", "changepass", "cp"
    };

    @EventHandler(priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        String command = event.getMessage();
        String censoredCommand = censorCommand(command);

        // The unsafe built-in logger is disabled; emit a safe panel audit instead.
        Bukkit.getLogger().info(player.getName() + " issued server command: " + censoredCommand);

        // Log command (censor sensitive commands)
        if (plugin.getSecurityBot() != null && plugin.getConfigManager().isCommandLogEnabled()) {
            plugin.getSecurityBot().logCommand(player.getName(), censoredCommand);
        }

        boolean bypassed = plugin.getConfigManager().isPlayerBypassed(player.getName());

        // Check blocked commands
        if (plugin.getConfigManager().isCommandBlocked(command)) {
            if (!bypassed) {
                event.setCancelled(true);
                String msg = plugin.getLanguageManager().get("security.command-blocked");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                return;
            }
        }

        // Check verification
        if (!plugin.getConfigManager().isCommandVerificationEnabled()) return;
        if (!plugin.getConfigManager().isCommandRestricted(command)) return;
        if (isFromBypassedPlugin()) {
            plugin.allowGameModeChange(player.getUniqueId());
            return;
        }
        if (bypassed) {
            plugin.allowGameModeChange(player.getUniqueId());
            return;
        }

        String cmd = command.startsWith("/") ? command.substring(1) : command;
        if (plugin.removeVerifiedCommand(player.getName() + ":" + cmd)) {
            plugin.allowGameModeChange(player.getUniqueId());
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.getLanguageManager().msg("security.command-verify-required"));

        String id = plugin.getVerifyManager().addPendingCommand(player.getName(), command, false);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().sendVerifyRequest(id, player.getName(), command, false);
        }
    }

    private boolean isFromBypassedPlugin() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String cn = e.getClassName().toLowerCase();
            for (String p : plugin.getConfigManager().getBypassPluginPrefixes()) {
                if (cn.contains(p.toLowerCase())) return true;
            }
        }
        return false;
    }

    /**
     * Censor passwords in sensitive commands.
     * /login mypass -> /login ****
     */
    public static String censorCommand(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = cmd.trim().split("\\s+");
        if (parts.length == 0) return command;

        String base = parts[0].toLowerCase();
        if (base.contains(":")) base = base.substring(base.indexOf(":") + 1);

        for (String s : SENSITIVE_COMMANDS) {
            if (base.equals(s)) {
                return "/" + parts[0] + " ***";
            }
        }
        return command;
    }

    public static boolean isSensitiveCommand(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        String base = cmd.trim().split("\\s+")[0].toLowerCase();
        if (base.contains(":")) base = base.substring(base.indexOf(":") + 1);
        for (String s : SENSITIVE_COMMANDS) {
            if (base.equals(s)) return true;
        }
        return false;
    }
}
