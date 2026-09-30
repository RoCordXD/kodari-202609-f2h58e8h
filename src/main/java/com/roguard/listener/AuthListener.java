package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AuthListener implements Listener {

    private final RoGuard plugin;
    private final Map<UUID, BukkitTask> kickTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks = new HashMap<>();

    public AuthListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        beginAuthentication(event.getPlayer());
    }

    public void beginAuthentication(Player player) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;

        UUID uuid = player.getUniqueId();

        // Avoid duplicate timers after plugin reloads or repeated initialization.
        stopAll(uuid);

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        data.setLastName(player.getName());
        plugin.getPlayerDataManager().saveData(uuid);

        // The login dialog already authenticated this player during the
        // configuration phase, so do not log them out again.
        if (dialogAuthHandled(uuid)) {
            plugin.getAuthManager().handlePostLogin(player);
            return;
        }

        plugin.getAuthManager().logout(uuid);
        plugin.getTwoFactorManager().removePendingPlayer2FA(uuid);
        plugin.getTwoFactorManager().removePendingStaff2FA(uuid);

        // Session resume
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";
        if (plugin.getConfigManager().getAuthConfig().getBoolean("session-enabled", false)
                && !ip.isEmpty()
                && plugin.getAuthManager().checkSession(uuid, ip)) {
            plugin.getAuthManager().setLoggedIn(uuid, true);
            player.sendMessage(plugin.getLanguageManager().msg("auth.login-success"));
            onPlayerLoggedIn(uuid);
            plugin.getAuthManager().handlePostLogin(player);
            return;
        }

        // Delay the first title so join/resource-pack titles cannot overwrite it.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || plugin.getAuthManager().isLoggedIn(uuid)) return;
            boolean registered = plugin.getPlayerDataManager().getData(uuid).isRegistered();
            showTitle(player, registered);
            sendPrompt(player, registered);
        }, 10L);

        // Repeat the center title until authentication succeeds.
        BukkitTask reminder = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || plugin.getAuthManager().isLoggedIn(uuid)
                    || dialogAuthHandled(uuid)) {
                stopReminder(uuid);
                return;
            }
            showTitle(player, plugin.getPlayerDataManager().getData(uuid).isRegistered());
            sendPrompt(player, plugin.getPlayerDataManager().getData(uuid).isRegistered());
        }, 40L, 60L);
        reminderTasks.put(uuid, reminder);

        // Kick timeout
        int timeout = plugin.getConfigManager().getLoginTimeout();
        BukkitTask kick = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.getAuthManager().isLoggedIn(uuid)) {
                player.kickPlayer(plugin.getLanguageManager().get("auth.kick-not-logged-in"));
            }
        }, timeout * 20L);
        kickTasks.put(uuid, kick);
    }

    /** True when the dialog already completed auth for this connection. */
    private boolean dialogAuthHandled(UUID uuid) {
        return plugin.getConfigManager().isAuthDialogEnabled()
                && com.roguard.dialog.DialogManager.isSupported()
                && plugin.getAuthManager().isLoggedIn(uuid);
    }

    private void showTitle(Player player, boolean registered) {
        String title;
        String subtitle;

        if (registered) {
            title = plugin.getLanguageManager().get("auth.title-login");
            subtitle = plugin.getLanguageManager().get("auth.title-login-subtitle");
        } else {
            title = plugin.getLanguageManager().get("auth.title-register");
            subtitle = plugin.getLanguageManager().get("auth.title-register-subtitle");
        }

        if (isMissing(title)) title = registered ? "LOGIN" : "REGISTER";
        if (isMissing(subtitle)) subtitle = registered ? "Use /login <password>" : "Use /register <password> <password>";

        player.sendTitle(title, subtitle, 0, 70, 10);
    }

    private boolean isMissing(String value) {
        return value == null || value.isBlank() || value.contains("Missing:");
    }

    private void sendPrompt(Player player, boolean registered) {
        if (registered) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.prompt-login"));
        } else {
            player.sendMessage(plugin.getLanguageManager().msg("auth.prompt-register"));
        }
    }

    public void stopAll(UUID uuid) {
        BukkitTask k = kickTasks.remove(uuid);
        if (k != null) k.cancel();
        stopReminder(uuid);
        // Clear title after login
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) player.resetTitle();
    }

    private void stopReminder(UUID uuid) {
        BukkitTask r = reminderTasks.remove(uuid);
        if (r != null) r.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getAuthManager().logout(uuid);
        plugin.getTwoFactorManager().removePendingPlayer2FA(uuid);
        plugin.getTwoFactorManager().removePendingStaff2FA(uuid);
        stopAll(uuid);
    }

    // Call after successful login to stop reminders
    public void onPlayerLoggedIn(UUID uuid) {
        stopAll(uuid);
    }

    // ==================== RESTRICTIONS ====================

    private boolean isRestricted(UUID uuid) {
        if (!plugin.getConfigManager().isAuthEnabled()) return false;
        return !plugin.getAuthManager().isLoggedIn(uuid) || plugin.getTwoFactorManager().isPendingAny2FA(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!isRestricted(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String cmd = event.getMessage().toLowerCase().split(" ")[0];
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(":") + 1);

        if (!plugin.getAuthManager().isLoggedIn(uuid)) {
            boolean allowed = false;
            for (String a : plugin.getConfigManager().getAllowedCommands()) {
                if (cmd.equalsIgnoreCase(a)) { allowed = true; break; }
            }
            if (!allowed) {
                event.setCancelled(true);
                sendPrompt(player, plugin.getPlayerDataManager().getData(uuid).isRegistered());
            }
            return;
        }

        if (plugin.getTwoFactorManager().isPendingAny2FA(uuid)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-required"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (isRestricted(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerPickupItemEvent event) {
        if (isRestricted(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
}
