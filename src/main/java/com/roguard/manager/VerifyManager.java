package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerifyManager {

    private final RoGuard plugin;
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    public VerifyManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public String addPendingCommand(String sender, String command, boolean isConsole) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        pendingCommands.put(id, new PendingCommand(sender, command, isConsole, System.currentTimeMillis()));

        int timeout = plugin.getConfigManager().getProtectConfig().getInt("verify-timeout", 60);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> pendingCommands.remove(id), timeout * 20L);

        return id;
    }

    public PendingCommand removePendingCommand(String id) {
        return pendingCommands.remove(id);
    }

    public static class PendingCommand {
        private final String sender;
        private final String command;
        private final boolean console;
        private final long timestamp;

        public PendingCommand(String sender, String command, boolean console, long timestamp) {
            this.sender = sender;
            this.command = command;
            this.console = console;
            this.timestamp = timestamp;
        }

        public String getSender() { return sender; }
        public String getCommand() { return command; }
        public boolean isConsole() { return console; }
        public long getTimestamp() { return timestamp; }
    }
}
