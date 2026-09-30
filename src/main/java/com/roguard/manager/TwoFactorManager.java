package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TwoFactorManager {

    private final RoGuard plugin;
    
    // Staff 2FA
    private final Set<UUID> pendingStaff2FA = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> staffVerifyIds = new ConcurrentHashMap<>();
    
    // Player 2FA
    private final Set<UUID> pendingPlayer2FA = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> playerVerifyIds = new ConcurrentHashMap<>();
    
    // Link codes (6-digit code -> UUID)
    private final Map<String, UUID> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> titleTasks = new ConcurrentHashMap<>();

    public TwoFactorManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== STAFF 2FA ====================

    public boolean isPendingStaff2FA(UUID uuid) {
        return pendingStaff2FA.contains(uuid);
    }

    public void addPendingStaff2FA(UUID uuid) {
        pendingStaff2FA.add(uuid);
    }

    public void removePendingStaff2FA(UUID uuid) {
        pendingStaff2FA.remove(uuid);
        cancelTitleIfFinished(uuid);
    }

    public String createStaff2FARequest(UUID uuid) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        staffVerifyIds.put(id, uuid);
        return id;
    }

    public UUID removeStaff2FARequest(String id) {
        return staffVerifyIds.remove(id);
    }

    public void unfreezeStaff(UUID uuid) {
        removePendingStaff2FA(uuid);
        finishVerification(uuid);
    }

    // ==================== PLAYER 2FA ====================

    public boolean isPendingPlayer2FA(UUID uuid) {
        return pendingPlayer2FA.contains(uuid);
    }

    public void addPendingPlayer2FA(UUID uuid) {
        pendingPlayer2FA.add(uuid);
    }

    public void removePendingPlayer2FA(UUID uuid) {
        pendingPlayer2FA.remove(uuid);
        cancelTitleIfFinished(uuid);
    }

    public String createPlayer2FARequest(UUID uuid) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        playerVerifyIds.put(id, uuid);
        return id;
    }

    public UUID removePlayer2FARequest(String id) {
        return playerVerifyIds.remove(id);
    }

    public void unfreezePlayer(UUID uuid) {
        removePendingPlayer2FA(uuid);
        finishVerification(uuid);
    }

    public void show2FATitle(Player player) {
        UUID uuid = player.getUniqueId();
        titleTasks.computeIfAbsent(uuid, ignored -> Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !isPendingAny2FA(uuid)) {
                cancelTitleTask(uuid);
                return;
            }

            player.sendTitle(
                    plugin.getLanguageManager().get("2fa.title-required"),
                    plugin.getLanguageManager().get("2fa.title-required-subtitle"),
                    5, 80, 10
            );
        }, 0L, 80L));
    }

    private void cancelTitleIfFinished(UUID uuid) {
        if (!isPendingAny2FA(uuid)) {
            cancelTitleTask(uuid);
        }
    }

    private void cancelTitleTask(UUID uuid) {
        BukkitTask task = titleTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void finishVerification(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                // A staff member can require both player 2FA and staff 2FA.
                if (isPendingAny2FA(uuid)) {
                    show2FATitle(player);
                    return;
                }

                cancelTitleTask(uuid);
                player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-success"));
                player.sendTitle(
                        plugin.getLanguageManager().get("2fa.title-success"),
                        plugin.getLanguageManager().get("2fa.title-success-subtitle"),
                        5, 40, 10
                );
                Bukkit.getScheduler().runTaskLater(plugin, player::resetTitle, 55L);
            });
        }
    }

    // ==================== LINK CODES ====================

    public String generateLinkCode(UUID uuid) {
        // Remove old codes for this player
        linkCodes.entrySet().removeIf(e -> e.getValue().equals(uuid));

        // Generate 6-digit code
        String code = String.format("%06d", new Random().nextInt(1000000));
        linkCodes.put(code, uuid);

        // Schedule expiration
        int expireTime = plugin.getConfigManager().getCodeExpireTime();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            linkCodes.remove(code);
        }, expireTime * 20L);

        return code;
    }

    public UUID verifyLinkCode(String code) {
        return linkCodes.remove(code);
    }

    // ==================== COMBINED CHECK ====================

    public boolean isPendingAny2FA(UUID uuid) {
        return isPendingStaff2FA(uuid) || isPendingPlayer2FA(uuid);
    }
}
