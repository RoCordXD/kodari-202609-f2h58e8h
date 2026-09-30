package com.roguard.command;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class TwoFACommand implements CommandExecutor {

    private final RoGuard plugin;

    public TwoFACommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.must-be-player"));
            return true;
        }

        if (!plugin.getConfigManager().isPlayer2FAEnabled()) {
            player.sendMessage(plugin.getLanguageManager().getPrefix() + "&c2FA is disabled.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.start-usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start":
                handleStart(player);
                break;
            case "disable":
                handleDisable(player);
                break;
            default:
                player.sendMessage(plugin.getLanguageManager().msg("2fa.start-usage"));
        }
        return true;
    }

    private void handleStart(Player player) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(player.getUniqueId());

        if (data.isLinked()) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.start-already-linked"));
            return;
        }

        // Generate 6-digit code
        String code = plugin.getTwoFactorManager().generateLinkCode(player.getUniqueId());

        player.sendMessage(plugin.getLanguageManager().msg("2fa.start-code-sent", "%code%", code));
        player.sendMessage(plugin.getLanguageManager().msg("2fa.start-code-expire"));
    }

    private void handleDisable(Player player) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(player.getUniqueId());

        if (!data.isLinked()) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.disable-not-linked"));
            return;
        }

        data.setLinked(false);
        data.setDiscordId(null);
        plugin.getPlayerDataManager().saveData(player.getUniqueId());

        player.sendMessage(plugin.getLanguageManager().msg("2fa.disable-success"));
    }
}
