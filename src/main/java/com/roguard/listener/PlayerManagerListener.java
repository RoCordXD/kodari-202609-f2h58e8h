package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.dialog.PlayerManagerDialog;
import com.roguard.inventory.LiveInventoryManager;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Handles player manager dialog clicks and keeps edited inventories in sync. */
public class PlayerManagerListener implements Listener {

    private final RoGuard plugin;

    /** Viewer UUID -> current search filter. */
    private final Map<UUID, String> filters = new HashMap<>();

    public PlayerManagerListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== DIALOG ACTIONS ====================

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Key key = event.getIdentifier();
        if (!key.namespace().equals("roguard")) return;

        String path = key.value();
        if (!path.startsWith("pm/")) return;

        Player viewer = connection.getPlayer();
        if (!viewer.hasPermission("roguard.playermanager")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        DialogResponseView view = event.getDialogResponseView();

        // Remember whatever is typed in the search box.
        if (view != null) {
            String typed = view.getText(PlayerManagerDialog.IN_SEARCH);
            if (typed != null) {
                filters.put(viewer.getUniqueId(), typed);
            }
        }

        String filter = filters.getOrDefault(viewer.getUniqueId(), "");

        if (path.equals(PlayerManagerDialog.DialogKeys.PM_SEARCH.value())
                || path.equals(PlayerManagerDialog.DialogKeys.PM_OPEN.value())
                || path.equals(PlayerManagerDialog.DialogKeys.PM_BACK.value())) {
            showList(viewer, filter, 0);
            return;
        }

        if (path.startsWith(PlayerManagerDialog.DialogKeys.PAGE_PREFIX)) {
            int page = parseInt(path.substring(PlayerManagerDialog.DialogKeys.PAGE_PREFIX.length()));
            showList(viewer, filter, page);
            return;
        }

        if (path.startsWith(PlayerManagerDialog.DialogKeys.VIEW_PREFIX)) {
            UUID target = parseUuid(path.substring(PlayerManagerDialog.DialogKeys.VIEW_PREFIX.length()));
            if (target == null) return;
            showPlayer(viewer, target);
            return;
        }

        if (path.startsWith(PlayerManagerDialog.DialogKeys.INV_PREFIX)) {
            UUID target = parseUuid(path.substring(PlayerManagerDialog.DialogKeys.INV_PREFIX.length()));
            if (target == null) return;
            openInventory(viewer, target);
            return;
        }

        if (path.startsWith(PlayerManagerDialog.DialogKeys.ENDER_PREFIX)) {
            UUID target = parseUuid(path.substring(PlayerManagerDialog.DialogKeys.ENDER_PREFIX.length()));
            if (target == null) return;
            openEnderChest(viewer, target);
            return;
        }

        // pm/form/<action>/<uuid>
        if (path.startsWith(PlayerManagerDialog.DialogKeys.FORM_PREFIX)) {
            String rest = path.substring(PlayerManagerDialog.DialogKeys.FORM_PREFIX.length());
            int split = rest.indexOf('/');
            if (split <= 0) return;

            String action = rest.substring(0, split);
            UUID target = parseUuid(rest.substring(split + 1));
            if (target == null) return;

            showPunishForm(viewer, target, action);
            return;
        }

        // pm/apply/<action>/<uuid>
        if (path.startsWith(PlayerManagerDialog.DialogKeys.APPLY_PREFIX)) {
            String rest = path.substring(PlayerManagerDialog.DialogKeys.APPLY_PREFIX.length());
            int split = rest.indexOf('/');
            if (split <= 0) return;

            String action = rest.substring(0, split);
            UUID target = parseUuid(rest.substring(split + 1));
            if (target == null) return;

            String reason = view == null ? null : view.getText(PlayerManagerDialog.IN_REASON);
            String duration = view == null ? null : view.getText(PlayerManagerDialog.IN_DURATION);
            applyPunishment(viewer, target, action, reason, duration);
        }
    }

    // ==================== PUNISHMENTS ====================

    private void showPunishForm(Player viewer, UUID targetId, String action) {
        if (!viewer.hasPermission("roguard.punish")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            try {
                if (action.equals("clear")) {
                    viewer.showDialog(plugin.getPlayerManagerDialog().createConfirmForm(target, action));
                } else if (action.startsWith("temp")) {
                    viewer.showDialog(plugin.getPlayerManagerDialog().createTimedForm(target, action));
                } else {
                    viewer.showDialog(plugin.getPlayerManagerDialog().createReasonForm(target, action));
                }
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
                plugin.getLogger().warning("Punishment form failed: " + error.getMessage());
            }
        });
    }

    private void openEnderChest(Player viewer, UUID targetId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.ender-offline"));
                return;
            }
            plugin.getLiveInventoryManager().openEnderChest(viewer, target);
        });
    }

    private void applyPunishment(Player viewer, UUID targetId, String action,
                                 String reason, String duration) {
        if (!viewer.hasPermission("roguard.punish")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        String finalReason = (reason == null || reason.isBlank())
                ? plugin.getLanguageManager().get("playermanager.default-reason")
                : reason;

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            String name = target.getName() == null ? targetId.toString() : target.getName();
            var punishments = plugin.getPunishmentManager();

            // Clear inventory does not use reasons or durations.
            if (action.equals("clear")) {
                if (target.isOnline() && target.getPlayer() != null) {
                    plugin.getLiveInventoryManager().clearInventory(target.getPlayer());
                } else {
                    plugin.getInventorySnapshotStore().clearSnapshot(targetId);
                }
                viewer.sendMessage(plugin.getLanguageManager()
                        .msg("playermanager.applied-clear", "%player%", name));
                showPlayer(viewer, targetId);
                return;
            }

            Long expiresAt = null;
            if (action.startsWith("temp")) {
                long millis = com.roguard.punishment.PunishmentManager.parseDuration(duration);
                if (millis <= 0) {
                    viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.invalid-duration"));
                    return;
                }
                expiresAt = System.currentTimeMillis() + millis;
            }

            switch (action) {
                case "ban", "tempban" ->
                        punishments.ban(target, finalReason, viewer.getName(), expiresAt);
                case "banip", "tempbanip" -> {
                    String ip = punishments.resolveIp(target);
                    if (ip == null || ip.isEmpty()) {
                        viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.no-ip"));
                        return;
                    }
                    punishments.ban(target, finalReason, viewer.getName(), expiresAt);
                    punishments.banIp(target, finalReason, viewer.getName(), expiresAt);
                }
                case "mute", "tempmute" ->
                        punishments.mute(targetId, finalReason, viewer.getName(), expiresAt);
                case "unban" -> punishments.unban(target);
                case "unmute" -> punishments.unmute(targetId);
                default -> {
                    return;
                }
            }

            String key = "playermanager.applied-" + action;
            String message = plugin.getLanguageManager().get(key);
            if (message == null || message.isBlank() || message.contains("Missing:")) {
                message = "\u00a7aAction applied to %player%.";
            }

            String time = expiresAt == null ? ""
                    : com.roguard.punishment.PunishmentManager.formatRemaining(
                            expiresAt - System.currentTimeMillis());

            viewer.sendMessage(plugin.getLanguageManager().getPrefix()
                    + message.replace("%player%", name)
                             .replace("%reason%", finalReason)
                             .replace("%time%", time));

            // Return to the player view so the buttons refresh.
            showPlayer(viewer, targetId);
        });
    }

    private void showList(Player viewer, String filter, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                viewer.showDialog(plugin.getPlayerManagerDialog().createListDialog(filter, page));
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
                plugin.getLogger().warning("Player manager list failed: " + error.getMessage());
            }
        });
    }

    private void showPlayer(Player viewer, UUID targetId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            try {
                viewer.showDialog(plugin.getPlayerManagerDialog().createPlayerDialog(target));
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
                plugin.getLogger().warning("Player detail dialog failed: " + error.getMessage());
            }
        });
    }

    private void openInventory(Player viewer, UUID targetId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null && target.isOnline()) {
                plugin.getLiveInventoryManager().open(viewer, target);
            } else {
                // Offline players are edited through the stored snapshot.
                plugin.getLiveInventoryManager()
                        .openOffline(viewer, Bukkit.getOfflinePlayer(targetId));
            }
        });
    }

    // ==================== INVENTORY SYNC ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getLiveInventoryManager().isManaged(top)) return;

        // Filler slots exist only in the full 54 slot view.
        if (plugin.getLiveInventoryManager().isFullView(top)
                && event.getClickedInventory() == top
                && LiveInventoryManager.isLocked(event.getRawSlot())) {
            event.setCancelled(true);
            return;
        }

        scheduleSync(event.getWhoClicked().getUniqueId(), top);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getLiveInventoryManager().isManaged(top)) return;

        if (plugin.getLiveInventoryManager().isFullView(top)) {
            for (int slot : event.getRawSlots()) {
                if (slot < top.getSize() && LiveInventoryManager.isLocked(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        scheduleSync(event.getWhoClicked().getUniqueId(), top);
    }

    /** Applies the edit to the target on the next tick, once Bukkit finished the click. */
    private void scheduleSync(UUID viewerId, Inventory gui) {
        LiveInventoryManager manager = plugin.getLiveInventoryManager();
        LiveInventoryManager.Session session = manager.getSession(viewerId);
        if (session == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (session.offline) {
                plugin.getInventorySnapshotStore().saveFromGui(session.targetId, gui);
                return;
            }
            Player target = Bukkit.getPlayer(session.targetId);
            if (target == null || !target.isOnline()) return;

            if (manager.isEnderView(gui)) {
                manager.pushEnderChest(gui, target);
            } else {
                manager.pushToPlayer(gui, target);
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getLiveInventoryManager().isManaged(top)) return;

        UUID viewerId = event.getPlayer().getUniqueId();
        LiveInventoryManager.Session session = plugin.getLiveInventoryManager().getSession(viewerId);

        if (session != null) {
            if (session.offline) {
                plugin.getInventorySnapshotStore().saveFromGui(session.targetId, top);
            } else {
                Player target = Bukkit.getPlayer(session.targetId);
                if (target != null && target.isOnline()) {
                    if (plugin.getLiveInventoryManager().isEnderView(top)) {
                        plugin.getLiveInventoryManager().pushEnderChest(top, target);
                    } else {
                        plugin.getLiveInventoryManager().pushToPlayer(top, target);
                    }
                }
            }
        }

        plugin.getLiveInventoryManager().close(viewerId);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        // Apply any edit made while this player was offline.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.getInventorySnapshotStore().applyPending(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getLiveInventoryManager().close(player.getUniqueId());
        filters.remove(player.getUniqueId());

        // Keep a fresh snapshot so offline editing stays accurate.
        plugin.getInventorySnapshotStore().saveFromPlayer(player);
    }

    // ==================== HELPERS ====================

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
