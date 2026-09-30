package com.roguard.punishment;

import com.roguard.RoGuard;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles bans, IP bans and mutes. */
public class PunishmentManager {

    private static final Pattern DURATION = Pattern.compile("(?i)(\\d+)\\s*([smhdw])");

    private final RoGuard plugin;
    private final File muteFile;
    private YamlConfiguration mutes;

    public PunishmentManager(RoGuard plugin) {
        this.plugin = plugin;
        this.muteFile = new File(plugin.getDataFolder(), "mutes.yml");
        load();
    }

    private void load() {
        if (!muteFile.exists()) {
            try {
                muteFile.getParentFile().mkdirs();
                muteFile.createNewFile();
            } catch (IOException ignored) {}
        }
        mutes = YamlConfiguration.loadConfiguration(muteFile);
    }

    private void save() {
        try {
            mutes.save(muteFile);
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save mutes.yml: " + error.getMessage());
        }
    }

    // ==================== DURATION ====================

    /**
     * Parses durations such as 30m, 2h, 7d, 1w or 1d12h.
     * Returns -1 when nothing valid was found.
     */
    public static long parseDuration(String input) {
        if (input == null || input.isBlank()) return -1;

        Matcher matcher = DURATION.matcher(input.trim());
        long total = 0;
        boolean found = false;

        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();
            found = true;

            switch (unit) {
                case "s" -> total += TimeUnit.SECONDS.toMillis(amount);
                case "m" -> total += TimeUnit.MINUTES.toMillis(amount);
                case "h" -> total += TimeUnit.HOURS.toMillis(amount);
                case "d" -> total += TimeUnit.DAYS.toMillis(amount);
                case "w" -> total += TimeUnit.DAYS.toMillis(amount * 7);
                default -> { }
            }
        }

        return found && total > 0 ? total : -1;
    }

    /** Human readable remaining time. */
    public static String formatRemaining(long millis) {
        if (millis <= 0) return "0s";

        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        StringBuilder result = new StringBuilder();
        if (days > 0) result.append(days).append("d ");
        if (hours > 0) result.append(hours).append("h ");
        if (minutes > 0) result.append(minutes).append("m ");
        if (result.isEmpty()) result.append(seconds).append("s");

        return result.toString().trim();
    }

    // ==================== BAN ====================

    public void ban(OfflinePlayer target, String reason, String source, Long expiresAt) {
        String name = target.getName();
        if (name == null) return;

        Date expiry = expiresAt == null ? null : new Date(expiresAt);
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expiry, source);

        Player online = target.isOnline() ? target.getPlayer() : null;
        if (online != null) {
            Bukkit.getScheduler().runTask(plugin, () -> online.kickPlayer(buildKickMessage(reason, expiresAt)));
        }
    }

    public void banIp(OfflinePlayer target, String reason, String source, Long expiresAt) {
        String ip = resolveIp(target);
        if (ip == null || ip.isEmpty()) return;

        Date expiry = expiresAt == null ? null : new Date(expiresAt);
        Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, expiry, source);

        // Kick everyone currently using that address.
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getAddress() == null) continue;
            if (!ip.equals(online.getAddress().getAddress().getHostAddress())) continue;
            Bukkit.getScheduler().runTask(plugin, () -> online.kickPlayer(buildKickMessage(reason, expiresAt)));
        }
    }

    public boolean isBanned(OfflinePlayer target) {
        String name = target.getName();
        return name != null && Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
    }

    public void unban(OfflinePlayer target) {
        String name = target.getName();
        if (name == null) return;

        Bukkit.getBanList(BanList.Type.NAME).pardon(name);

        String ip = resolveIp(target);
        if (ip != null && !ip.isEmpty()) {
            Bukkit.getBanList(BanList.Type.IP).pardon(ip);
        }
    }

    private String buildKickMessage(String reason, Long expiresAt) {
        String key = expiresAt == null ? "punishment.ban-screen" : "punishment.tempban-screen";
        String message = plugin.getLanguageManager().get(key);

        if (message == null || message.isBlank() || message.contains("Missing:")) {
            message = expiresAt == null
                    ? "\u00a7cYou are banned.\n\u00a7fReason: %reason%"
                    : "\u00a7cYou are temporarily banned.\n\u00a7fReason: %reason%\n\u00a7fExpires in: %time%";
        }

        String remaining = expiresAt == null
                ? "" : formatRemaining(expiresAt - System.currentTimeMillis());

        return message.replace("%reason%", reason).replace("%time%", remaining);
    }

    /** Last known IP from RoGuard's own player data. */
    public String resolveIp(OfflinePlayer target) {
        Player online = target.isOnline() ? target.getPlayer() : null;
        if (online != null && online.getAddress() != null) {
            return online.getAddress().getAddress().getHostAddress();
        }
        return plugin.getPlayerDataManager().getData(target.getUniqueId()).getLastIP();
    }

    // ==================== MUTE ====================

    public void mute(UUID uuid, String reason, String source, Long expiresAt) {
        String path = uuid.toString();
        mutes.set(path + ".reason", reason);
        mutes.set(path + ".source", source);
        mutes.set(path + ".expires", expiresAt == null ? -1L : expiresAt);
        mutes.set(path + ".created", System.currentTimeMillis());
        save();
    }

    public void unmute(UUID uuid) {
        mutes.set(uuid.toString(), null);
        save();
    }

    public boolean isMuted(UUID uuid) {
        String path = uuid.toString();
        if (!mutes.contains(path)) return false;

        long expires = mutes.getLong(path + ".expires", -1L);
        if (expires > 0 && System.currentTimeMillis() > expires) {
            unmute(uuid);
            return false;
        }
        return true;
    }

    public String getMuteReason(UUID uuid) {
        return mutes.getString(uuid.toString() + ".reason", "No reason provided");
    }

    /** Returns -1 for a permanent mute. */
    public long getMuteExpiry(UUID uuid) {
        return mutes.getLong(uuid.toString() + ".expires", -1L);
    }

    /** Message shown when a muted player tries to talk. */
    public String buildMuteMessage(UUID uuid) {
        long expires = getMuteExpiry(uuid);
        String key = expires > 0 ? "punishment.muted-temp" : "punishment.muted";
        String message = plugin.getLanguageManager().get(key);

        if (message == null || message.isBlank() || message.contains("Missing:")) {
            message = expires > 0
                    ? "\u00a7cYou are muted for %time%. Reason: %reason%"
                    : "\u00a7cYou are muted. Reason: %reason%";
        }

        String remaining = expires > 0
                ? formatRemaining(expires - System.currentTimeMillis()) : "";

        return message.replace("%reason%", getMuteReason(uuid)).replace("%time%", remaining);
    }

    public void reload() {
        load();
    }
}
