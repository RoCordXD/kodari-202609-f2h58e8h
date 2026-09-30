package com.roguard.config;

import com.roguard.RoGuard;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LanguageManager {

    private final RoGuard plugin;
    private FileConfiguration lang;
    private File langFile;

    public LanguageManager(RoGuard plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        langFile = new File(plugin.getDataFolder(), "language.yml");
        if (!langFile.exists()) plugin.saveResource("language.yml", false);
        lang = YamlConfiguration.loadConfiguration(langFile);
        applyDefaults();
    }

    public void reload() {
        lang = YamlConfiguration.loadConfiguration(langFile);
        applyDefaults();
    }

    private void applyDefaults() {
        try (InputStream stream = plugin.getResource("language.yml")) {
            if (stream == null) return;

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            );
            lang.setDefaults(defaults);
            lang.options().copyDefaults(true);
            lang.save(langFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Failed to update language.yml defaults: " + error.getMessage());
        }
    }

    public String get(String path) {
        String msg = lang.getString(path, "&cMissing: " + path);
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public String get(String path, String... replacements) {
        String msg = get(path);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace(replacements[i], replacements[i + 1]);
            }
        }
        return msg;
    }

    public String getPrefix() {
        return get("prefix");
    }

    public String msg(String path) {
        return getPrefix() + get(path);
    }

    public String msg(String path, String... replacements) {
        return getPrefix() + get(path, replacements);
    }

    /** True when the key is missing or blank. */
    public boolean isMissing(String path) {
        String value = lang.getString(path);
        return value == null || value.isBlank();
    }

    // Discord messages (no prefix, no color)
    public String getDiscord(String key) {
        return lang.getString("discord." + key, "");
    }
}
