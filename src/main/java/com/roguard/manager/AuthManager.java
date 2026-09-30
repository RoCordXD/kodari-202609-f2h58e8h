package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AuthManager {

    private static final String HASH_PREFIX = "pbkdf2";
    private static final int HASH_ITERATIONS = 60000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final RoGuard plugin;
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> loginAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionCache = new ConcurrentHashMap<>();

    public AuthManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedIn.contains(uuid);
    }

    public void setLoggedIn(UUID uuid, boolean logged) {
        if (logged) {
            loggedIn.add(uuid);
            loginAttempts.remove(uuid);
            sessionCache.put(uuid, System.currentTimeMillis());
            
            PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
            data.setLastLogin(System.currentTimeMillis());
            plugin.getPlayerDataManager().saveData(uuid);
        } else {
            loggedIn.remove(uuid);
        }
    }

    public boolean checkSession(UUID uuid, String ip) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        if (data.getLastIP() != null && data.getLastIP().equals(ip)) {
            Long lastSession = sessionCache.get(uuid);
            if (lastSession != null) {
                long timeout = plugin.getConfigManager().getAuthConfig().getInt("session-timeout", 30) * 60000L;
                if (System.currentTimeMillis() - lastSession < timeout) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean register(UUID uuid, String password) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        if (data.isRegistered()) return false;

        data.setPassword(hashPassword(password));
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            data.setLastName(player.getName());
            data.setLastIP(player.getAddress().getAddress().getHostAddress());
        }
        plugin.getPlayerDataManager().saveData(uuid);
        return true;
    }

    public boolean login(UUID uuid, String password) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        if (!data.isRegistered()) return false;

        if (verifyPassword(password, data.getPassword())) {
            // Upgrade old SHA-256 records after the next successful login.
            if (!data.getPassword().startsWith(HASH_PREFIX + "$")) {
                data.setPassword(hashPassword(password));
            }
            setLoggedIn(uuid, true);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                data.setLastIP(player.getAddress().getAddress().getHostAddress());
            }
            plugin.getPlayerDataManager().saveData(uuid);
            return true;
        }
        
        incrementAttempts(uuid);
        return false;
    }

    public boolean changePassword(UUID uuid, String oldPass, String newPass) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        if (!verifyPassword(oldPass, data.getPassword())) return false;

        data.setPassword(hashPassword(newPass));
        plugin.getPlayerDataManager().saveData(uuid);
        return true;
    }

    public void incrementAttempts(UUID uuid) {
        int attempts = loginAttempts.getOrDefault(uuid, 0) + 1;
        loginAttempts.put(uuid, attempts);

        if (attempts >= plugin.getConfigManager().getMaxLoginAttempts()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.kickPlayer(plugin.getLanguageManager().get("auth.kick-too-many-attempts"));
                });
            }
        }
    }

    public void logout(UUID uuid) {
        loggedIn.remove(uuid);
    }

    // ==================== OFFLINE (CONFIGURATION PHASE) ====================

    /** Validate a password without requiring an online Player object. */
    public boolean verifyOffline(UUID uuid, String password) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        if (!data.isRegistered()) return false;
        return verifyPassword(password, data.getPassword());
    }

    /** Register from the configuration phase dialog. */
    public void registerOffline(UUID uuid, String name, String password, String ip) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        data.setPassword(hashPassword(password));
        data.setLastName(name);
        if (ip != null && !ip.isEmpty()) data.setLastIP(ip);
        data.setLastLogin(System.currentTimeMillis());
        plugin.getPlayerDataManager().saveData(uuid);
    }

    /** Persist a successful dialog login. */
    public void markLoginSuccess(UUID uuid, String name, String ip) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        data.setLastName(name);
        if (ip != null && !ip.isEmpty()) data.setLastIP(ip);
        data.setLastLogin(System.currentTimeMillis());

        // Upgrade legacy SHA-256 records transparently.
        loginAttempts.remove(uuid);
        sessionCache.put(uuid, System.currentTimeMillis());
        plugin.getPlayerDataManager().saveData(uuid);
    }

    /**
     * Called AFTER a successful login.
     * Triggers Player 2FA and Staff 2FA if applicable.
     */
    public void handlePostLogin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);

        boolean needs2FA = false;

        // Player 2FA (Discord linked)
        if (plugin.getConfigManager().isPlayer2FAEnabled() && data.isLinked()
                && data.getDiscordId() != null && !data.getDiscordId().isEmpty()) {
            plugin.getTwoFactorManager().addPendingPlayer2FA(uuid);
            needs2FA = true;

            if (plugin.getTwoFABot() != null) {
                String verifyId = plugin.getTwoFactorManager().createPlayer2FARequest(uuid);
                plugin.getTwoFABot().sendPlayer2FAVerify(data.getDiscordId(), player.getName(), verifyId);
            }
        }

        // Staff 2FA
        if (plugin.getConfigManager().isStaff2FAEnabled()
                && plugin.getConfigManager().isStaffMember(player.getName())) {
            plugin.getTwoFactorManager().addPendingStaff2FA(uuid);
            needs2FA = true;

            String discordId = plugin.getConfigManager().getStaffDiscordId(player.getName());
            if (discordId != null && !discordId.isEmpty() && plugin.getSecurityBot() != null) {
                String verifyId = plugin.getTwoFactorManager().createStaff2FARequest(uuid);
                plugin.getSecurityBot().sendStaff2FAVerify(discordId, player.getName(), verifyId);
            }
        }

        if (needs2FA) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-required"));
            plugin.getTwoFactorManager().show2FATitle(player);
        } else {
            player.resetTitle();
        }
    }

    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] hash = deriveKey(password, salt, HASH_ITERATIONS);
            return HASH_PREFIX + "$" + HASH_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash a password securely.", e);
        }
    }

    private boolean verifyPassword(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;

        try {
            if (stored.startsWith(HASH_PREFIX + "$")) {
                String[] parts = stored.split("\\$", 4);
                if (parts.length != 4) return false;

                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = deriveKey(password, salt, iterations);
                return MessageDigest.isEqual(expected, actual);
            }

            // Compatibility with records created by older RoGuard builds.
            return MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.UTF_8),
                    legacySha256(password).getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] deriveKey(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private String legacySha256(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : hash) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
