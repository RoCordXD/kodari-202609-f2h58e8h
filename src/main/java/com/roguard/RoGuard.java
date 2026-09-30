package com.roguard;

import com.roguard.config.ConfigManager;
import com.roguard.config.LanguageManager;
import com.roguard.discord.*;
import com.roguard.command.*;
import com.roguard.listener.*;
import com.roguard.manager.*;
import com.roguard.antivpn.VPNChecker;
import com.roguard.antibot.AntiBotManager;
import com.roguard.dialog.DialogManager;
import com.roguard.dialog.PlayerManagerDialog;
import com.roguard.inventory.LiveInventoryManager;
import com.roguard.inventory.InventorySnapshotStore;
import com.roguard.punishment.PunishmentManager;
import com.roguard.logging.SensitiveLogFilter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class RoGuard extends JavaPlugin {

    private static RoGuard instance;
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private SecurityBot securityBot;
    private ChatBot chatBot;
    private TwoFABot twoFABot;
    private VerifyManager verifyManager;
    private TwoFactorManager twoFactorManager;
    private AuthManager authManager;
    private PlayerDataManager playerDataManager;
    private VPNChecker vpnChecker;
    private AntiBotManager antiBotManager;
    private DialogManager dialogManager;
    private PlayerManagerDialog playerManagerDialog;
    private LiveInventoryManager liveInventoryManager;
    private InventorySnapshotStore inventorySnapshotStore;
    private PunishmentManager punishmentManager;
    private AuthListener authListener;

    private final Set<String> verifiedCommands = ConcurrentHashMap.newKeySet();
    private final Set<UUID> gameModeBypass = ConcurrentHashMap.newKeySet();
    private final Set<UUID> shopkeeperBypass = ConcurrentHashMap.newKeySet();

    @Override
    public void onLoad() {
        // Must be FIRST - before any auth command can be logged
        try {
            SensitiveLogFilter.install();
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Failed to install password log protection.", error);
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.loadAll();
        languageManager = new LanguageManager(this);

        playerDataManager = new PlayerDataManager(this);
        authManager = new AuthManager(this);
        verifyManager = new VerifyManager(this);
        twoFactorManager = new TwoFactorManager(this);
        vpnChecker = new VPNChecker(this);
        antiBotManager = new AntiBotManager(this);
        dialogManager = new DialogManager(this);
        playerManagerDialog = new PlayerManagerDialog(this);
        liveInventoryManager = new LiveInventoryManager(this);
        inventorySnapshotStore = new InventorySnapshotStore(this);
        punishmentManager = new PunishmentManager(this);

        startSecurityBot();
        startChatBot();
        start2FABot();

        authListener = new AuthListener(this);

        Bukkit.getPluginManager().registerEvents(new CommandListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ConsoleCommandListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AntiVPNListener(this), this);
        Bukkit.getPluginManager().registerEvents(authListener, this);
        Bukkit.getPluginManager().registerEvents(new GameModeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShopkeeperListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AntiBotListener(this), this);

        // Dialog-based features only work on servers that ship the Paper Dialog API.
        if (DialogManager.isSupported()) {
            try {
                Bukkit.getPluginManager().registerEvents(new AuthDialogListener(this), this);
                Bukkit.getPluginManager().registerEvents(new PlayerManagerListener(this), this);
                getLogger().info("Dialog API detected - dialog login and player manager enabled.");
            } catch (Throwable error) {
                getLogger().warning("Could not enable dialog features: " + error.getMessage());
            }
        } else {
            getLogger().info("Dialog API not available - using chat-based login.");
        }

        // Also initialize players when the plugin is enabled through a live reload.
        Bukkit.getScheduler().runTask(this, () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                authListener.beginAuthentication(player);
            }
        });

        getCommand("roguard").setExecutor(new RoGuardCommand(this));
        getCommand("roguard").setTabCompleter(new RoGuardCommand(this));

        AuthCommand authCmd = new AuthCommand(this);
        getCommand("changepassword").setExecutor(authCmd);
        getCommand("unregister").setExecutor(authCmd);

        getCommand("2fa").setExecutor(new TwoFACommand(this));

        if (configManager.isConsoleMirrorEnabled()) {
            try { ConsoleLogAppender.register(this); } catch (Exception ignored) {}
        }

        getLogger().info("RoGuard has been enabled!");
    }

    private void startSecurityBot() {
        try {
            String token = configManager.getProtectConfig().getString("security-bot-token", "");
            if (token != null && !token.isEmpty() && !token.equals("YOUR_SECURITY_BOT_TOKEN_HERE")) {
                securityBot = new SecurityBot(this, token);
                securityBot.start();
                getLogger().info("Security Bot started!");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start Security Bot!", e);
        }
    }

    private void startChatBot() {
        try {
            String token = configManager.getDiscordChatConfig().getString("chat-bot-token", "");
            if (token != null && !token.isEmpty() && !token.equals("YOUR_CHAT_BOT_TOKEN_HERE")) {
                chatBot = new ChatBot(this, token);
                chatBot.start();
                getLogger().info("Chat Bot started!");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start Chat Bot!", e);
        }
    }

    private void start2FABot() {
        try {
            String token = configManager.get2FAConfig().getString("bot-token", "");
            if (token != null && !token.isEmpty() && !token.equals("YOUR_2FA_BOT_TOKEN_HERE")) {
                twoFABot = new TwoFABot(this, token);
                twoFABot.start();
                getLogger().info("2FA Bot started!");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start 2FA Bot!", e);
        }
    }

    @Override
    public void onDisable() {
        if (configManager != null) configManager.clearAllTempBypass();
        if (playerDataManager != null) playerDataManager.saveAll();

        // Snapshot everyone so offline inventory editing stays accurate.
        if (inventorySnapshotStore != null) {
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                inventorySnapshotStore.saveFromPlayer(online);
            }
        }

        ConsoleLogAppender.unregister();

        if (chatBot != null) chatBot.sendServerStatus(false);
        if (securityBot != null) securityBot.shutdown();
        if (chatBot != null) chatBot.shutdown();
        if (twoFABot != null) twoFABot.shutdown();

        SensitiveLogFilter.uninstall();

        getLogger().info("RoGuard has been disabled!");
    }

    public void addVerifiedCommand(String key) { verifiedCommands.add(key); }
    public boolean removeVerifiedCommand(String key) { return verifiedCommands.remove(key); }

    public void allowGameModeChange(UUID uuid) { gameModeBypass.add(uuid); }
    public boolean consumeGameModeBypass(UUID uuid) { return gameModeBypass.remove(uuid); }

    public void grantShopkeeperBypass(UUID uuid) {
        shopkeeperBypass.add(uuid);
        int seconds = configManager.getShopkeeperBypassDuration();
        Bukkit.getScheduler().runTaskLater(this, () -> shopkeeperBypass.remove(uuid), seconds * 20L);
    }
    public boolean hasShopkeeperBypass(UUID uuid) { return shopkeeperBypass.contains(uuid); }

    public static RoGuard getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public SecurityBot getSecurityBot() { return securityBot; }
    public ChatBot getChatBot() { return chatBot; }
    public TwoFABot getTwoFABot() { return twoFABot; }
    public VerifyManager getVerifyManager() { return verifyManager; }
    public TwoFactorManager getTwoFactorManager() { return twoFactorManager; }
    public AuthManager getAuthManager() { return authManager; }
    public AuthListener getAuthListener() { return authListener; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public VPNChecker getVPNChecker() { return vpnChecker; }
    public AntiBotManager getAntiBotManager() { return antiBotManager; }
    public DialogManager getDialogManager() { return dialogManager; }
    public PlayerManagerDialog getPlayerManagerDialog() { return playerManagerDialog; }
    public LiveInventoryManager getLiveInventoryManager() { return liveInventoryManager; }
    public InventorySnapshotStore getInventorySnapshotStore() { return inventorySnapshotStore; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
}
