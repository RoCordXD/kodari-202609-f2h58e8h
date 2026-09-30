package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final RoGuard plugin;
    private final File dataFolder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public PlayerDataManager(RoGuard plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        this.dataFolder.mkdirs();
    }

    public PlayerData getData(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> loadData(id));
    }

    public PlayerData getDataByName(String name) {
        for (PlayerData data : cache.values()) {
            if (data.getLastName() != null && data.getLastName().equalsIgnoreCase(name)) {
                return data;
            }
        }
        // Search files
        File[] files = dataFolder.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                if (name.equalsIgnoreCase(cfg.getString("last-name", ""))) {
                    UUID uuid = UUID.fromString(f.getName().replace(".yml", ""));
                    return getData(uuid);
                }
            }
        }
        return null;
    }

    private PlayerData loadData(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        PlayerData data = new PlayerData(uuid);
        
        if (file.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            data.setPassword(cfg.getString("password"));
            data.setLastName(cfg.getString("last-name"));
            data.setDiscordId(cfg.getString("discord-id"));
            data.setLinked(cfg.getBoolean("linked", false));
            data.setLastIP(cfg.getString("last-ip"));
            data.setLastLogin(cfg.getLong("last-login", 0));
        }
        
        return data;
    }

    public void saveData(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;

        File file = new File(dataFolder, uuid.toString() + ".yml");
        YamlConfiguration cfg = new YamlConfiguration();
        
        cfg.set("password", data.getPassword());
        cfg.set("last-name", data.getLastName());
        cfg.set("discord-id", data.getDiscordId());
        cfg.set("linked", data.isLinked());
        cfg.set("last-ip", data.getLastIP());
        cfg.set("last-login", data.getLastLogin());

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save player data: " + uuid);
        }
    }

    public void saveAll() {
        for (UUID uuid : cache.keySet()) {
            saveData(uuid);
        }
    }

    public boolean deleteData(String name) {
        PlayerData data = getDataByName(name);
        if (data == null) return false;

        cache.remove(data.getUuid());
        File file = new File(dataFolder, data.getUuid().toString() + ".yml");
        return file.delete();
    }

    public static class PlayerData {
        private final UUID uuid;
        private String password;
        private String lastName;
        private String discordId;
        private boolean linked;
        private String lastIP;
        private long lastLogin;

        public PlayerData(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUuid() { return uuid; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        
        public String getDiscordId() { return discordId; }
        public void setDiscordId(String discordId) { this.discordId = discordId; }
        
        public boolean isLinked() { return linked; }
        public void setLinked(boolean linked) { this.linked = linked; }
        
        public String getLastIP() { return lastIP; }
        public void setLastIP(String lastIP) { this.lastIP = lastIP; }
        
        public long getLastLogin() { return lastLogin; }
        public void setLastLogin(long lastLogin) { this.lastLogin = lastLogin; }

        public boolean isRegistered() { return password != null && !password.isEmpty(); }
    }
}
