package com.roguard.inventory;

import com.roguard.RoGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OpenInv-style live inventory editor.
 *
 * Layout of the 54 slot view:
 *   0-26   main storage   (player slots 9-35)
 *   27-35  hotbar         (player slots 0-8)
 *   36-39  armor          (boots, leggings, chestplate, helmet)
 *   40     offhand
 *   41-44  crafting grid  (2x2, only while the player has their own inventory open)
 *   45-53  filler
 */
public class LiveInventoryManager {

    public static final int SIZE = 54;

    public static final int ARMOR_START = 36;   // 36..39
    public static final int OFFHAND_SLOT = 40;
    public static final int CRAFT_START = 41;   // 41..44
    public static final int FILLER_START = 45;

    private final RoGuard plugin;

    /** Viewer UUID -> open session. */
    private final Map<UUID, Session> sessions = new HashMap<>();

    public LiveInventoryManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== OPEN ====================

    /** Opens an online player's inventory with live two-way syncing. */
    public boolean open(Player viewer, Player target) {
        close(viewer.getUniqueId());

        Holder holder = new Holder(target.getUniqueId(), false);
        Inventory gui = Bukkit.createInventory(holder, SIZE, buildTitle(target.getName(), false));
        holder.inventory = gui;

        pullFromPlayer(gui, target);
        decorate(gui);

        viewer.openInventory(gui);

        // Refresh the view so external changes made to the player show up.
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(target.getUniqueId());
            if (live == null || !live.isOnline() || !viewer.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) viewer.closeInventory();
                });
                close(viewer.getUniqueId());
                return;
            }
            pullFromPlayer(gui, live);
        }, 20L, 20L);

        sessions.put(viewer.getUniqueId(), new Session(target.getUniqueId(), gui, task, false));
        return true;
    }

    /**
     * Opens an offline player's inventory from the stored snapshot.
     * Changes are written back to the snapshot and applied on their next join.
     */
    public boolean openOffline(Player viewer, OfflinePlayer target) {
        close(viewer.getUniqueId());

        UUID targetId = target.getUniqueId();
        String name = target.getName() == null ? targetId.toString() : target.getName();

        Holder holder = new Holder(targetId, true);
        Inventory gui = Bukkit.createInventory(holder, SIZE, buildTitle(name, true));
        holder.inventory = gui;

        if (!plugin.getInventorySnapshotStore().load(targetId, gui)) {
            viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.no-snapshot"));
            return false;
        }
        decorate(gui);

        viewer.openInventory(gui);
        sessions.put(viewer.getUniqueId(), new Session(targetId, gui, null, true));
        return true;
    }

    // ==================== ENDER CHEST ====================

    /** Opens an online player's ender chest with live syncing. */
    public boolean openEnderChest(Player viewer, Player target) {
        close(viewer.getUniqueId());

        String raw = plugin.getLanguageManager().get("playermanager.enderchest-title");
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) {
            raw = "Ender Chest: " + target.getName();
        } else {
            raw = raw.replace("%player%", target.getName());
        }

        EnderHolder holder = new EnderHolder(target.getUniqueId());
        Inventory gui = Bukkit.createInventory(holder, 27,
                LegacyComponentSerializer.legacySection().deserialize(raw));
        holder.inventory = gui;

        gui.setContents(target.getEnderChest().getContents());
        viewer.openInventory(gui);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(target.getUniqueId());
            if (live == null || !live.isOnline() || !viewer.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) viewer.closeInventory();
                });
                close(viewer.getUniqueId());
                return;
            }
            gui.setContents(live.getEnderChest().getContents());
        }, 20L, 20L);

        sessions.put(viewer.getUniqueId(), new Session(target.getUniqueId(), gui, task, false));
        return true;
    }

    /** Writes an ender chest view back onto the target. */
    public void pushEnderChest(Inventory gui, Player target) {
        target.getEnderChest().setContents(gui.getContents());
    }

    public boolean isEnderView(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof EnderHolder;
    }

    // ==================== CLEAR ====================

    /** Wipes an online player's inventory, armor and offhand. */
    public void clearInventory(Player target) {
        PlayerInventory inv = target.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        target.updateInventory();
    }

    private Component buildTitle(String playerName, boolean offline) {
        String key = offline
                ? "playermanager.inventory-title-offline"
                : "playermanager.inventory-title";

        String raw = plugin.getLanguageManager().get(key);
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) {
            raw = offline ? "Offline: " + playerName : "Inventory: " + playerName;
        } else {
            raw = raw.replace("%player%", playerName);
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    public void close(UUID viewerId) {
        Session session = sessions.remove(viewerId);
        if (session != null && session.task != null) {
            session.task.cancel();
        }
    }

    public Session getSession(UUID viewerId) {
        return sessions.get(viewerId);
    }

    public boolean isManaged(Inventory inventory) {
        return inventory != null
                && (inventory.getHolder() instanceof Holder
                 || inventory.getHolder() instanceof EnderHolder);
    }

    /** True only for the 54 slot full inventory view. */
    public boolean isFullView(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    // ==================== SYNC ====================

    /** Copies the player's items into the GUI. */
    public void pullFromPlayer(Inventory gui, Player target) {
        PlayerInventory inv = target.getInventory();

        // Storage: player 9..35 -> gui 0..26
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, clone(inv.getItem(i + 9)));
        }
        // Hotbar: player 0..8 -> gui 27..35
        for (int i = 0; i < 9; i++) {
            gui.setItem(27 + i, clone(inv.getItem(i)));
        }

        // Armor: Bukkit order is boots, leggings, chestplate, helmet
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            gui.setItem(ARMOR_START + i, clone(armor[i]));
        }

        gui.setItem(OFFHAND_SLOT, clone(inv.getItemInOffHand()));

        // Crafting grid is only reachable while the player has their own inventory open.
        ItemStack[] crafting = readCrafting(target);
        for (int i = 0; i < 4; i++) {
            gui.setItem(CRAFT_START + i, crafting == null ? null : clone(crafting[i]));
        }
    }

    /** Writes the GUI contents back onto the player. */
    public void pushToPlayer(Inventory gui, Player target) {
        PlayerInventory inv = target.getInventory();

        for (int i = 0; i < 27; i++) {
            inv.setItem(i + 9, clone(gui.getItem(i)));
        }
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, clone(gui.getItem(27 + i)));
        }

        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            armor[i] = clone(gui.getItem(ARMOR_START + i));
        }
        inv.setArmorContents(armor);

        ItemStack off = clone(gui.getItem(OFFHAND_SLOT));
        inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);

        writeCrafting(target, gui);

        target.updateInventory();
    }

    private ItemStack[] readCrafting(Player target) {
        try {
            InventoryView view = target.getOpenInventory();
            Inventory top = view.getTopInventory();
            if (top.getType() != InventoryType.CRAFTING || top.getSize() < 5) return null;

            ItemStack[] grid = new ItemStack[4];
            for (int i = 0; i < 4; i++) {
                grid[i] = top.getItem(i + 1); // slot 0 is the result
            }
            return grid;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeCrafting(Player target, Inventory gui) {
        try {
            InventoryView view = target.getOpenInventory();
            Inventory top = view.getTopInventory();
            if (top.getType() != InventoryType.CRAFTING || top.getSize() < 5) return;

            for (int i = 0; i < 4; i++) {
                top.setItem(i + 1, clone(gui.getItem(CRAFT_START + i)));
            }
        } catch (Throwable ignored) {
            // The player is browsing another inventory; skip the crafting grid.
        }
    }

    // ==================== DECORATION ====================

    private void decorate(Inventory gui) {
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = FILLER_START; i < SIZE; i++) {
            gui.setItem(i, filler);
        }
    }

    private ItemStack pane(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** True when the slot is a filler and must never be edited. */
    public static boolean isLocked(int slot) {
        return slot >= FILLER_START;
    }

    private ItemStack clone(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        return stack.clone();
    }

    // ==================== TYPES ====================

    public static class Session {
        public final UUID targetId;
        public final Inventory inventory;
        public final BukkitTask task;
        public final boolean offline;

        public Session(UUID targetId, Inventory inventory, BukkitTask task, boolean offline) {
            this.targetId = targetId;
            this.inventory = inventory;
            this.task = task;
            this.offline = offline;
        }
    }

    /** Marks an inventory as a RoGuard ender chest view. */
    public static class EnderHolder implements InventoryHolder {
        public final UUID targetId;
        private Inventory inventory;

        public EnderHolder(UUID targetId) {
            this.targetId = targetId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Marks an inventory as a RoGuard managed view. */
    public static class Holder implements InventoryHolder {
        public final UUID targetId;
        public final boolean offline;
        private Inventory inventory;

        public Holder(UUID targetId, boolean offline) {
            this.targetId = targetId;
            this.offline = offline;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
