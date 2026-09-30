package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.event.*;
import org.bukkit.event.server.ServerCommandEvent;

public class ConsoleCommandListener implements Listener {

    private final RoGuard plugin;

    public ConsoleCommandListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        // Normalise: strip leading slash + trim so verify keys always match
        String raw = event.getCommand() == null ? "" : event.getCommand().trim();
        String command = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (command.isEmpty()) return;

        if (plugin.getSecurityBot() != null && plugin.getConfigManager().isCommandLogEnabled()) {
            plugin.getSecurityBot().logCommand("CONSOLE", CommandListener.censorCommand("/" + command));
        }

        if (!plugin.getConfigManager().isCommandVerificationEnabled()) return;
        if (!plugin.getConfigManager().isCommandRestricted(command)) return;
        if (isFromBypassedPlugin()) return;

        if (plugin.removeVerifiedCommand("console:" + command)) return;

        event.setCancelled(true);
        plugin.getLogger().warning("[Security] Console command blocked: /" + command);

        String id = plugin.getVerifyManager().addPendingCommand("CONSOLE", "/" + command, true);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().sendVerifyRequest(id, "CONSOLE", "/" + command, true);
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
}
