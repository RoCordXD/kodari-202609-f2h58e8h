package com.roguard.listener;

import com.roguard.RoGuard;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final RoGuard plugin;

    public PlayerListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== CHAT ====================

    /**
     * Paper's modern chat event. Mutes are enforced here because
     * AsyncPlayerChatEvent no longer fires on current Paper builds.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChatMute(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.getPunishmentManager().isMuted(uuid)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getPunishmentManager().buildMuteMessage(uuid));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        if (plugin.getChatBot() == null) return;
        if (!plugin.getAuthManager().isLoggedIn(event.getPlayer().getUniqueId())) return;

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getChatBot().sendMinecraftChat(event.getPlayer().getName(), message);
    }

    /** Legacy chat fallback for servers still firing the old event. */
    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onLegacyChatMute(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.getPunishmentManager().isMuted(uuid)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getPunishmentManager().buildMuteMessage(uuid));
        }
    }

    // ==================== JOIN / QUIT ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getChatBot() != null) {
            plugin.getChatBot().sendJoinLeaveMessage(player.getName(), true,
                    Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();

        plugin.getConfigManager().removeTempBypass(name);

        if (plugin.getConfigManager().isAutoDeOpEnabled() && player.isOp()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setOp(false);
                plugin.getLogger().info("Auto-deoped: " + name);
            });
        }

        if (plugin.getChatBot() != null) {
            int online = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
            plugin.getChatBot().sendJoinLeaveMessage(name, false, online, Bukkit.getMaxPlayers());
        }
    }

    // ==================== DEATH ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        String message = event.getDeathMessage();
        if (message != null && plugin.getChatBot() != null) {
            plugin.getChatBot().sendDeathMessage(event.getEntity().getName(), message);
        }
    }

    // ==================== ADVANCEMENT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!isRealAdvancement(event.getAdvancement())) return;

        if (plugin.getChatBot() != null) {
            plugin.getChatBot().sendAdvancementMessage(
                    event.getPlayer().getName(),
                    event.getAdvancement().getKey().getKey()
            );
        }
    }

    /**
     * Minecraft fires this event for hidden technical advancements too,
     * such as the recipe unlock root named "On Inventory Change".
     * Only advancements that the client actually announces are relayed.
     */
    private boolean isRealAdvancement(Advancement advancement) {
        String key = advancement.getKey().getKey().toLowerCase();

        // Recipe unlocks and internal roots are never announced in game.
        if (key.startsWith("recipes/") || key.contains("/recipes/")
                || key.equals("root") || key.endsWith("/root")) {
            return false;
        }

        // Display data is absent on hidden technical advancements. Paper's modern
        // API uses doesAnnounceToChat(), while Bukkit's legacy API uses
        // shouldAnnounceChat(). Reflection keeps this compatible across both.
        try {
            var display = advancement.getDisplay();
            if (display == null) return false;

            try {
                Object value = display.getClass()
                        .getMethod("doesAnnounceToChat")
                        .invoke(display);
                if (value instanceof Boolean announced) return announced;
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the legacy Bukkit method.
            }

            try {
                Object value = display.getClass()
                        .getMethod("shouldAnnounceChat")
                        .invoke(display);
                if (value instanceof Boolean announced) return announced;
            } catch (ReflectiveOperationException ignored) {
                // The key and display-null checks still filter technical entries.
            }

            return true;
        } catch (Throwable ignored) {
            // Older API: fall back to the key filtering above.
            return true;
        }
    }
}
