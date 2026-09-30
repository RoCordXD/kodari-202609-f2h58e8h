package com.roguard.command;

import com.roguard.RoGuard;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import java.util.*;

public class RoGuardCommand implements CommandExecutor, TabCompleter {

    private final RoGuard plugin;

    public RoGuardCommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("roguard.reload")) {
                    sender.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
                    return true;
                }
                plugin.getConfigManager().reloadAll();
                if (plugin.getAntiBotManager() != null) {
                    plugin.getAntiBotManager().reset();
                }
                sender.sendMessage(plugin.getLanguageManager().msg("general.config-reloaded"));
                break;

            case "status":
                if (!sender.hasPermission("roguard.status")) {
                    sender.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
                    return true;
                }
                showStatus(sender);
                break;

            case "settings":
                openSettings(sender);
                break;

            case "playermanager":
            case "pm":
                openPlayerManager(sender);
                break;

            default:
                sendHelp(sender);
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "RoGuard Status");
        sender.sendMessage(ChatColor.GRAY + "Security Bot: " + getStatus(plugin.getSecurityBot() != null));
        sender.sendMessage(ChatColor.GRAY + "Chat Bot: " + getStatus(plugin.getChatBot() != null));
        sender.sendMessage(ChatColor.GRAY + "2FA Bot: " + getStatus(plugin.getTwoFABot() != null));
        sender.sendMessage(ChatColor.GOLD + "Features:");
        sender.sendMessage(ChatColor.GRAY + "  Auth: " + getStatus(plugin.getConfigManager().isAuthEnabled()));
        sender.sendMessage(ChatColor.GRAY + "  Player 2FA: " + getStatus(plugin.getConfigManager().isPlayer2FAEnabled()));
        sender.sendMessage(ChatColor.GRAY + "  Staff 2FA: " + getStatus(plugin.getConfigManager().isStaff2FAEnabled()));
        sender.sendMessage(ChatColor.GRAY + "  Command Verify: " + getStatus(plugin.getConfigManager().isCommandVerificationEnabled()));
        sender.sendMessage(ChatColor.GRAY + "  Anti-VPN: " + getStatus(plugin.getConfigManager().isAntiVPNEnabled()));
    }

    private void openSettings(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.must-be-player"));
            return;
        }
        if (!player.hasPermission("roguard.settings")) {
            player.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }
        if (!com.roguard.dialog.DialogManager.isSupported()) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            return;
        }

        try {
            player.showDialog(plugin.getDialogManager().createSettingsDialog());
        } catch (Throwable error) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            plugin.getLogger().warning("Failed to open settings dialog: " + error.getMessage());
        }
    }

    private void openPlayerManager(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.must-be-player"));
            return;
        }
        if (!player.hasPermission("roguard.playermanager")) {
            player.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }
        if (!com.roguard.dialog.DialogManager.isSupported()) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            return;
        }

        try {
            player.showDialog(plugin.getPlayerManagerDialog().createListDialog("", 0));
        } catch (Throwable error) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            plugin.getLogger().warning("Failed to open player manager: " + error.getMessage());
        }
    }

    private String getStatus(boolean enabled) {
        return enabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "RoGuard Commands");
        sender.sendMessage(ChatColor.YELLOW + "/roguard reload " + ChatColor.GRAY + "- Reload config");
        sender.sendMessage(ChatColor.YELLOW + "/roguard status " + ChatColor.GRAY + "- Show status");
        sender.sendMessage(ChatColor.YELLOW + "/roguard settings " + ChatColor.GRAY + "- Open settings dialog");
        sender.sendMessage(ChatColor.YELLOW + "/roguard playermanager " + ChatColor.GRAY + "- Manage players");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("roguard.reload")) list.add("reload");
            if (sender.hasPermission("roguard.status")) list.add("status");
            if (sender.hasPermission("roguard.settings")) list.add("settings");
            if (sender.hasPermission("roguard.playermanager")) list.add("playermanager");
        }
        return list;
    }
}
