package com.roguard.inventory;

import com.roguard.RoGuard;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores a copy of every player's inventory so offline players can be edited.
 *
 * Snapshots are written on quit and on server shutdown, then applied again
 * the next time the player joins.
 */
public class InventorySnapshotStore {

    private final RoGuard plugin;
    private final File folder;

    public InventorySnapshotStore(RoGuard plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "inventories");
        this.folder.mkdirs();
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid.toString() + ".yml");
    }

    // ==================== SAVE ====================

    /** Writes the player's current inventory to disk. */
    public void saveFromPlayer(Player player) {
        PlayerInventory inv = player.getInventory();
        YamlConfiguration config = new YamlConfiguration();

        for (int i = 0; i < 36; i++) {
            config.set("slot." + i, inv.getItem(i));
        }

        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            config.set("armor." + i, armor[i]);
        }

        config.set("offhand", inv.getItemInOffHand());
        config.set("name", player.getName());
        config.set("saved", System.currentTimeMillis());

        write(player.getUniqueId(), config);
    }

    /** Writes an edited GUI back into the snapshot. */
    public void saveFromGui(UUID uuid, Inventory gui) {
        YamlConfiguration config = read(uuid);

        // Hotbar: gui 27..35 -> slots 0..8
        for (int i = 0; i < 9; i++) {
            config.set("slot." + i, gui.getItem(27 + i));
        }
        // Storage: gui 0..26 -> slots 9..35
        for (int i = 0; i < 27; i++) {
            config.set("slot." + (i + 9), gui.getItem(i));
        }
        for (int i = 0; i < 4; i++) {
            config.set("armor." + i, gui.getItem(LiveInventoryManager.ARMOR_START + i));
        }
        config.set("offhand", gui.getItem(LiveInventoryManager.OFFHAND_SLOT));
        config.set("pending", true);

        write(uuid, config);
    }

    // ==================== LOAD ====================

    /** Fills a GUI from the snapshot. Returns false when no snapshot exists. */
    public boolean load(UUID uuid, Inventory gui) {
        File file = fileFor(uuid);
        if (!file.exists()) return false;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        for (int i = 0; i < 9; i++) {
            gui.setItem(27 + i, config.getItemStack("slot." + i));
        }
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, config.getItemStack("slot." + (i + 9)));
        }
        for (int i = 0; i < 4; i++) {
            gui.setItem(LiveInventoryManager.ARMOR_START + i, config.getItemStack("armor." + i));
        }
        gui.setItem(LiveInventoryManager.OFFHAND_SLOT, config.getItemStack("offhand"));

        // The crafting grid cannot be restored for offline players.
        for (int i = 0; i < 4; i++) {
            gui.setItem(LiveInventoryManager.CRAFT_START + i, null);
        }

        return true;
    }

    /** Empties an offline player's stored inventory. */
    public void clearSnapshot(UUID uuid) {
        YamlConfiguration config = read(uuid);
        for (int i = 0; i < 36; i++) {
            config.set("slot." + i, null);
        }
        for (int i = 0; i < 4; i++) {
            config.set("armor." + i, null);
        }
        config.set("offhand", null);
        config.set("pending", true);
        write(uuid, config);
    }

    /** True when an admin edited this player while they were offline. */
    public boolean hasPendingEdit(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) return false;
        return YamlConfiguration.loadConfiguration(file).getBoolean("pending", false);
    }

    /** Applies a pending offline edit to a player who just joined. */
    public void applyPending(Player player) {
        UUID uuid = player.getUniqueId();
        if (!hasPendingEdit(uuid)) return;

        YamlConfiguration config = read(uuid);
        PlayerInventory inv = player.getInventory();

        for (int i = 0; i < 36; i++) {
            inv.setItem(i, config.getItemStack("slot." + i));
        }

        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            armor[i] = config.getItemStack("armor." + i);
        }
        inv.setArmorContents(armor);

        ItemStack offhand = config.getItemStack("offhand");
        inv.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);

        player.updateInventory();

        config.set("pending", false);
        write(uuid, config);

        player.sendMessage(plugin.getLanguageManager().msg("playermanager.inventory-updated"));
    }

    // ==================== IO ====================

    private YamlConfiguration read(UUID uuid) {
        File file = fileFor(uuid);
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private void write(UUID uuid, YamlConfiguration config) {
        try {
            config.save(fileFor(uuid));
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save inventory snapshot: " + error.getMessage());
        }
    }
}
