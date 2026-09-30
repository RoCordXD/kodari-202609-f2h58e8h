package com.roguard.command;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class AuthCommand implements CommandExecutor {

    private final RoGuard plugin;

    public AuthCommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase();

        switch (cmdName) {
            case "changepassword":
                return handleChangePassword(sender, args);
            case "unregister":
                return handleUnregister(sender, args);
        }
        return true;
    }

    private boolean handleChangePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.must-be-player"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-usage"));
            return true;
        }

        String oldPass = args[0];
        String newPass = args[1];

        if (oldPass.equals(newPass)) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-same"));
            return true;
        }

        if (newPass.length() < plugin.getConfigManager().getMinPasswordLength()) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.register-password-too-short"));
            return true;
        }

        if (plugin.getAuthManager().changePassword(player.getUniqueId(), oldPass, newPass)) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-success"));
        } else {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-wrong"));
        }
        return true;
    }

    private boolean handleUnregister(CommandSender sender, String[] args) {
        if (!sender.hasPermission("roguard.unregister")) {
            sender.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.getLanguageManager().msg("auth.unregister-usage"));
            return true;
        }

        String playerName = args[0];
        if (plugin.getPlayerDataManager().deleteData(playerName)) {
            sender.sendMessage(plugin.getLanguageManager().msg("auth.unregister-success", "%player%", playerName));
        } else {
            sender.sendMessage(plugin.getLanguageManager().msg("auth.unregister-not-found", "%player%", playerName));
        }
        return true;
    }
}
