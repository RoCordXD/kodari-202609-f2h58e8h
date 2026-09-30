package com.roguard.config;

import com.roguard.RoGuard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    private final RoGuard plugin;
    private FileConfiguration protectConfig, commandConfig, userBypassConfig, staffConfig;
    private FileConfiguration discordChatConfig, warningCommandConfig, authConfig, twoFAConfig;
    
    private File protectFile, commandFile, userBypassFile, staffFile;
    private File discordChatFile, warningCommandFile, authFile, twoFAFile;

    private final Set<String> tempBypassPlayers = ConcurrentHashMap.newKeySet();

    public ConfigManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.getDataFolder().mkdirs();
        new File(plugin.getDataFolder(), "playerdata").mkdirs();

        protectFile = loadConfig("protect.yml");
        protectConfig = YamlConfiguration.loadConfiguration(protectFile);

        commandFile = loadConfig("command.yml");
        commandConfig = YamlConfiguration.loadConfiguration(commandFile);

        userBypassFile = loadConfig("userbypass.yml");
        userBypassConfig = YamlConfiguration.loadConfiguration(userBypassFile);
        tempBypassPlayers.addAll(userBypassConfig.getStringList("temp-bypass"));

        staffFile = loadConfig("staff.yml");
        staffConfig = YamlConfiguration.loadConfiguration(staffFile);

        discordChatFile = loadConfig("discordchat.yml");
        discordChatConfig = YamlConfiguration.loadConfiguration(discordChatFile);

        warningCommandFile = loadConfig("warningcommand.yml");
        warningCommandConfig = YamlConfiguration.loadConfiguration(warningCommandFile);

        authFile = loadConfig("auth.yml");
        authConfig = YamlConfiguration.loadConfiguration(authFile);

        twoFAFile = loadConfig("2fa.yml");
        twoFAConfig = YamlConfiguration.loadConfiguration(twoFAFile);
    }

    private File loadConfig(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return file;
    }

    /** Persist every config file that can be changed at runtime. */
    public void saveAll() {
        try { protectConfig.save(protectFile); } catch (IOException ignored) {}
        try { authConfig.save(authFile); } catch (IOException ignored) {}
        try { twoFAConfig.save(twoFAFile); } catch (IOException ignored) {}
    }

    public void setFeature(String feature, boolean value) {
        protectConfig.set("features." + feature, value);
    }

    public void setProtectValue(String path, Object value) {
        protectConfig.set(path, value);
    }

    public void setAuthValue(String path, Object value) {
        authConfig.set(path, value);
    }

    public void set2FAValue(String path, Object value) {
        twoFAConfig.set(path, value);
    }

    /** Whether the configuration-phase login dialog is used. */
    public boolean isAuthDialogEnabled() {
        return authConfig.getBoolean("dialog-enabled", true);
    }

    /** Whether admins may open their own inventory through the player manager. */
    public boolean isSelfInventoryAllowed() {
        return protectConfig.getBoolean("player-manager.allow-self-inventory", false);
    }

    public void reloadAll() {
        protectConfig = YamlConfiguration.loadConfiguration(protectFile);
        commandConfig = YamlConfiguration.loadConfiguration(commandFile);
        userBypassConfig = YamlConfiguration.loadConfiguration(userBypassFile);
        staffConfig = YamlConfiguration.loadConfiguration(staffFile);
        discordChatConfig = YamlConfiguration.loadConfiguration(discordChatFile);
        warningCommandConfig = YamlConfiguration.loadConfiguration(warningCommandFile);
        authConfig = YamlConfiguration.loadConfiguration(authFile);
        twoFAConfig = YamlConfiguration.loadConfiguration(twoFAFile);
        plugin.getLanguageManager().reload();
    }

    // Bypass
    public boolean isPlayerBypassed(String name) {
        for (String n : userBypassConfig.getStringList("permanent-bypass"))
            if (n.equalsIgnoreCase(name)) return true;
        for (String n : tempBypassPlayers)
            if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    public void removeTempBypass(String name) {
        tempBypassPlayers.removeIf(n -> n.equalsIgnoreCase(name));
        saveTempBypass();
    }

    public void clearAllTempBypass() {
        tempBypassPlayers.clear();
        saveTempBypass();
    }

    private void saveTempBypass() {
        userBypassConfig.set("temp-bypass", new ArrayList<>(tempBypassPlayers));
        try { userBypassConfig.save(userBypassFile); } catch (IOException ignored) {}
    }

    // Commands
    public boolean isCommandBlocked(String cmd) {
        String c = cmd.toLowerCase().startsWith("/") ? cmd.substring(1).toLowerCase() : cmd.toLowerCase();
        for (String b : warningCommandConfig.getStringList("blocked-commands"))
            if (c.equals(b.toLowerCase()) || c.startsWith(b.toLowerCase() + " ")) return true;
        return false;
    }

    public boolean isCommandRestricted(String cmd) {
        String c = cmd.toLowerCase().startsWith("/") ? cmd.substring(1).toLowerCase() : cmd.toLowerCase();
        for (String r : commandConfig.getStringList("restricted-commands")) {
            if (r.equals("//") && c.startsWith("/")) return true;
            if (c.equals(r.toLowerCase()) || c.startsWith(r.toLowerCase() + " ")) return true;
        }
        return false;
    }

    public List<String> getBypassPluginPrefixes() {
        return commandConfig.getStringList("bypass-plugin-prefixes");
    }

    // Shopkeeper protection
    public boolean isShopkeeperVerifyEnabled() {
        return commandConfig.getBoolean("shopkeeper-protection.enabled", true);
    }

    public int getShopkeeperBypassDuration() {
        return commandConfig.getInt("shopkeeper-protection.bypass-duration", 60);
    }

    // Staff
    public String getStaffDiscordId(String mcName) {
        if (staffConfig.getConfigurationSection("staff") == null) return null;
        for (String key : staffConfig.getConfigurationSection("staff").getKeys(false)) {
            if (staffConfig.getString("staff." + key + ".MinecraftName", "").equalsIgnoreCase(mcName))
                return staffConfig.getString("staff." + key + ".DiscordID", "");
        }
        return null;
    }

    public boolean isStaffMember(String mcName) {
        String id = getStaffDiscordId(mcName);
        return id != null && !id.isEmpty() && !id.equals("000000000000000000");
    }

    // Features
    public boolean isFeatureEnabled(String f) { return protectConfig.getBoolean("features." + f, true); }
    public boolean isCommandVerificationEnabled() { return isFeatureEnabled("command-verification"); }
    public boolean isStaff2FAEnabled() { return isFeatureEnabled("staff-2fa"); }
    public boolean isCommandLogEnabled() { return isFeatureEnabled("command-log"); }
    public boolean isConsoleMirrorEnabled() { return isFeatureEnabled("console-mirror"); }
    public boolean isAutoDeOpEnabled() { return isFeatureEnabled("auto-deop"); }
    public boolean isAntiVPNEnabled() { return isFeatureEnabled("anti-vpn"); }

    // Anti-Bot
    public boolean isAntiBotEnabled() { return protectConfig.getBoolean("anti-bot.enabled", true); }
    public String getAntiBotKickMessage() { return protectConfig.getString("anti-bot.kick-message", "&cBot activity detected!"); }
    public int getAntiBotMaxJoins() { return protectConfig.getInt("anti-bot.max-joins", 8); }
    public int getAntiBotWindowSeconds() { return protectConfig.getInt("anti-bot.window-seconds", 10); }
    public int getAntiBotSameIpMaxJoins() { return protectConfig.getInt("anti-bot.same-ip-max-joins", 3); }
    public int getAntiBotSameIpMaxAccounts() { return protectConfig.getInt("anti-bot.same-ip-max-accounts", 3); }
    public boolean isAntiBotNameCheckEnabled() { return protectConfig.getBoolean("anti-bot.name-check", true); }
    public boolean isAntiBotPrefixPatternEnabled() { return protectConfig.getBoolean("anti-bot.prefix-pattern.enabled", true); }
    public int getAntiBotPrefixPatternThreshold() { return Math.max(2, protectConfig.getInt("anti-bot.prefix-pattern.threshold", 3)); }
    public int getAntiBotPrefixPatternWindowSeconds() { return Math.max(1, protectConfig.getInt("anti-bot.prefix-pattern.window-seconds", 15)); }
    public int getAntiBotPrefixPatternMinPrefixLength() { return Math.max(1, protectConfig.getInt("anti-bot.prefix-pattern.min-prefix-length", 3)); }
    public int getAntiBotPrefixPatternMinSuffixLength() { return Math.max(1, protectConfig.getInt("anti-bot.prefix-pattern.min-suffix-length", 3)); }
    public boolean isAntiBotBypassed(String name) {
        for (String n : protectConfig.getStringList("anti-bot.bypass-players"))
            if (n.equalsIgnoreCase(name)) return true;
        return false;
    }
    public List<String> getConsoleAllowedUserIds() { return protectConfig.getStringList("console-allowed-user-ids"); }

    // Anti-VPN
    public String getVPNKickMessage() { return protectConfig.getString("anti-vpn.kick-message", "&cVPN detected!"); }
    public boolean isVPNBypassed(String name) {
        for (String n : protectConfig.getStringList("anti-vpn.bypass-players"))
            if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    // Auth
    public boolean isAuthEnabled() { return authConfig.getBoolean("enabled", true); }
    public int getMinPasswordLength() { return authConfig.getInt("min-password-length", 6); }
    public int getLoginTimeout() { return authConfig.getInt("login-timeout", 60); }
    public int getMaxLoginAttempts() { return authConfig.getInt("max-login-attempts", 5); }
    public boolean isSessionEnabled() { return authConfig.getBoolean("session-enabled", false); }
    public List<String> getAllowedCommands() { return authConfig.getStringList("allowed-commands"); }

    // 2FA
    public boolean isPlayer2FAEnabled() { return twoFAConfig.getBoolean("enabled", true); }
    public int getCodeExpireTime() { return twoFAConfig.getInt("code-expire-time", 300); }

    // IP Response
    public List<String> getIpResponse() { return discordChatConfig.getStringList("ip-response"); }

    // Configs
    public FileConfiguration getProtectConfig() { return protectConfig; }
    public FileConfiguration getCommandConfig() { return commandConfig; }
    public FileConfiguration getDiscordChatConfig() { return discordChatConfig; }
    public FileConfiguration getAuthConfig() { return authConfig; }
    public FileConfiguration get2FAConfig() { return twoFAConfig; }
}
