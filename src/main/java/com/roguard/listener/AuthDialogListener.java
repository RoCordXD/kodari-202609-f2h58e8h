package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.dialog.DialogManager;
import com.roguard.manager.PlayerDataManager;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shows the login and register dialog while the player is still in the
 * configuration phase, so the vanilla dirt background stays visible
 * and the player never enters the world unauthenticated.
 */
public class AuthDialogListener implements Listener {

    private final RoGuard plugin;

    /** Connections waiting for a dialog answer. */
    private final Map<UUID, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    /** Live audience per connecting player so we can re-open the dialog on errors. */
    private final Map<UUID, Audience> audiences = new ConcurrentHashMap<>();
    /** Name cache (profile is unavailable after disconnect). */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Failed attempts per connection. */
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    public AuthDialogListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== CONFIGURATION PHASE ====================

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;
        if (!plugin.getConfigManager().isAuthDialogEnabled()) return;
        if (!DialogManager.isSupported()) return;

        PlayerConfigurationConnection connection = event.getConnection();
        UUID uuid = connection.getProfile().getId();
        String name = connection.getProfile().getName();
        if (uuid == null || name == null) return;

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
        data.setLastName(name);

        Audience audience = connection.getAudience();
        audiences.put(uuid, audience);
        names.put(uuid, name);
        attempts.put(uuid, 0);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        // Auto-fail after the configured login timeout.
        future.completeOnTimeout(false, plugin.getConfigManager().getLoginTimeout(), TimeUnit.SECONDS);
        pending.put(uuid, future);

        showAuthDialog(uuid, audience, name, null);

        boolean authenticated;
        try {
            authenticated = future.join();
        } catch (Exception ex) {
            authenticated = false;
        }

        if (!authenticated) {
            connection.disconnect(Component.text(
                    plugin.getLanguageManager().get("auth.kick-not-logged-in"), NamedTextColor.RED));
        } else {
            // Mark as logged in so AuthListener does not freeze the player afterwards.
            plugin.getAuthManager().setLoggedIn(uuid, true);
        }

        cleanup(uuid);
    }

    private void showAuthDialog(UUID uuid, Audience audience, String name, String error) {
        try {
            PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(uuid);
            if (data.isRegistered()) {
                audience.showDialog(plugin.getDialogManager().createLoginDialog(name, error));
            } else {
                audience.showDialog(plugin.getDialogManager().createRegisterDialog(name, error));
            }
        } catch (Throwable throwable) {
            // Version mismatch or unsupported dialog feature: never lock the whole
            // server out, but make the failure very visible to the admin.
            plugin.getLogger().severe("Could not show the auth dialog: " + throwable
                    + " - falling back to unrestricted join.");
            complete(uuid, true);
        }
    }

    // ==================== DIALOG BUTTON HANDLING ====================

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();

        if (key.equals(DialogManager.KEY_LOGIN)) {
            handleLogin(event);
        } else if (key.equals(DialogManager.KEY_REGISTER)) {
            handleRegister(event);
        } else if (key.equals(DialogManager.KEY_SETTINGS_SAVE)) {
            handleSettingsSave(event);
        }
    }

    private void handleLogin(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;

        UUID uuid = connection.getProfile().getId();
        if (uuid == null) return;

        DialogResponseView view = event.getDialogResponseView();
        String password = view == null ? null : view.getText(DialogManager.IN_PASSWORD);
        String name = names.getOrDefault(uuid, "Player");
        Audience audience = audiences.get(uuid);

        if (password == null || password.isEmpty()) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.login-usage"));
            return;
        }

        if (plugin.getAuthManager().verifyOffline(uuid, password)) {
            plugin.getAuthManager().markLoginSuccess(uuid, name, connectionIp(connection));
            complete(uuid, true);
        } else {
            int used = attempts.merge(uuid, 1, Integer::sum);
            if (used >= plugin.getConfigManager().getMaxLoginAttempts()) {
                complete(uuid, false);
                return;
            }
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.login-wrong-password"));
        }
    }

    private void handleRegister(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;

        UUID uuid = connection.getProfile().getId();
        if (uuid == null) return;

        DialogResponseView view = event.getDialogResponseView();
        String pass = view == null ? null : view.getText(DialogManager.IN_PASSWORD);
        String confirm = view == null ? null : view.getText(DialogManager.IN_PASSWORD_CONFIRM);
        String name = names.getOrDefault(uuid, "Player");
        Audience audience = audiences.get(uuid);

        if (pass == null || pass.isEmpty() || confirm == null || confirm.isEmpty()) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.register-usage"));
            return;
        }
        if (!pass.equals(confirm)) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.register-password-mismatch"));
            return;
        }
        if (pass.length() < plugin.getConfigManager().getMinPasswordLength()) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.register-password-too-short"));
            return;
        }

        plugin.getAuthManager().registerOffline(uuid, name, pass, connectionIp(connection));
        complete(uuid, true);
    }

    private void handleSettingsSave(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConnection)) return;

        Player player = gameConnection.getPlayer();
        if (!player.hasPermission("roguard.settings")) {
            player.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        DialogResponseView view = event.getDialogResponseView();
        if (view == null) return;

        // Read every toggle using the sanitized input ids and persist each feature.
        for (java.util.Map.Entry<String, String> entry : DialogManager.featureInputs().entrySet()) {
            Boolean value = view.getBoolean(entry.getKey());
            if (value != null) {
                plugin.getConfigManager().setFeature(entry.getValue(), value);
            }
        }

        Boolean antiBot = view.getBoolean(DialogManager.IN_ANTIBOT);
        if (antiBot != null) plugin.getConfigManager().setProtectValue("anti-bot.enabled", antiBot);

        Boolean auth = view.getBoolean(DialogManager.IN_AUTH);
        if (auth != null) plugin.getConfigManager().setAuthValue("enabled", auth);

        Boolean player2fa = view.getBoolean(DialogManager.IN_PLAYER2FA);
        if (player2fa != null) plugin.getConfigManager().set2FAValue("enabled", player2fa);

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfigManager().saveAll();
            player.sendMessage(plugin.getLanguageManager().msg("general.settings-saved"));
        });
    }

    // ==================== HELPERS ====================

    private String connectionIp(PlayerConfigurationConnection connection) {
        try {
            SocketAddress address = connection.getAddress();
            if (address instanceof InetSocketAddress inetAddress
                    && inetAddress.getAddress() != null) {
                return inetAddress.getAddress().getHostAddress();
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void retry(UUID uuid, Audience audience, String name, String error) {
        if (audience == null) return;
        // Showing another dialog replaces the current one on Paper 1.21.8.
        showAuthDialog(uuid, audience, name, error);
    }

    private void complete(UUID uuid, boolean success) {
        CompletableFuture<Boolean> future = pending.get(uuid);
        if (future != null) future.complete(success);
    }

    private void cleanup(UUID uuid) {
        pending.remove(uuid);
        audiences.remove(uuid);
        names.remove(uuid);
        attempts.remove(uuid);
    }

    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        cleanup(event.getPlayerUniqueId());
    }
}
