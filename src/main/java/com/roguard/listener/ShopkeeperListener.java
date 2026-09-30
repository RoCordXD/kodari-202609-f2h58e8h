package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class ShopkeeperListener implements Listener {

    private final RoGuard plugin;

    public ShopkeeperListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // Only sneak + right-click opens the Shopkeepers editor
        if (!event.getPlayer().isSneaking()) return;

        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();

        if (!isShopkeeper(entity)) return;

        // Feature toggle
        if (!plugin.getConfigManager().isShopkeeperVerifyEnabled()) return;
        if (!plugin.getConfigManager().isCommandVerificationEnabled()) return;

        // Player bypass
        if (plugin.getConfigManager().isPlayerBypassed(player.getName())) return;

        // Temporary approval window from a previous verification
        if (plugin.hasShopkeeperBypass(player.getUniqueId())) return;

        // Block the editor
        event.setCancelled(true);

        String shopName = entity.getCustomName() != null
                ? entity.getCustomName()
                : entity.getType().name();

        String action = "Shopkeeper Edit: " + shopName;

        player.sendMessage(plugin.getLanguageManager().msg("security.shopkeeper-verify-required"));

        String id = plugin.getVerifyManager().addPendingCommand(player.getName(), action, false);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().sendVerifyRequest(id, player.getName(), action, false);
        }
    }

    /**
     * Detect if an entity belongs to the Shopkeepers plugin.
     * Shopkeepers tags its entities with metadata "shopkeeper".
     */
    private boolean isShopkeeper(Entity entity) {
        if (entity == null) return false;

        // Metadata check (Shopkeepers plugin sets this)
        if (entity.hasMetadata("shopkeeper")) return true;

        // Scoreboard tag fallback
        if (entity.getScoreboardTags().contains("shopkeeper")) return true;

        // PersistentDataContainer fallback
        try {
            for (org.bukkit.NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
                if (key.getNamespace().equalsIgnoreCase("shopkeepers")) return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
