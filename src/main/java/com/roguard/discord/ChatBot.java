package com.roguard.discord;

import com.roguard.RoGuard;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class ChatBot extends ListenerAdapter {

    private final RoGuard plugin;
    private final String token;
    private JDA jda;

    public ChatBot(RoGuard plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start() throws Exception {
        try { JDALogger.setFallbackLoggerEnabled(false); } catch (Exception ignored) {}
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_EMOJIS_AND_STICKERS)
                .enableCache(CacheFlag.EMOJI)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .addEventListeners(this).build();
        jda.awaitReady();
        sendServerStatus(true);
    }

    public void shutdown() { if (jda != null) jda.shutdown(); }
    public JDA getJDA() { return jda; }

    private String lang(String key) {
        return plugin.getLanguageManager().getDiscord(key);
    }

    private TextChannel getChatChannel() {
        if (jda == null) return null;
        return jda.getTextChannelById(plugin.getConfigManager().getDiscordChatConfig().getString("mc-chat-channel-id", ""));
    }

    private String getWebhookUrl() {
        return plugin.getConfigManager().getDiscordChatConfig().getString("mc-chat-webhook-url", "");
    }

    // ==================== SERVER STATUS ====================

    public void sendServerStatus(boolean online) {
        TextChannel ch = getChatChannel();
        if (ch == null) return;
        String msg = lang(online ? "server-start" : "server-stop");
        if (msg.isEmpty()) msg = online ? "Server is now **online**!" : "Server is now **offline**.";
        msg = EmojiResolver.resolve(msg, ch.getJDA());
        EmbedBuilder e = new EmbedBuilder().setDescription(msg)
                .setColor(online ? new Color(0, 200, 0) : new Color(255, 0, 0)).setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== MC CHAT VIA WEBHOOK ====================

    public void sendMinecraftChat(String name, String message) {
        String webhookUrl = getWebhookUrl();
        if (webhookUrl.isEmpty() || webhookUrl.equals("YOUR_WEBHOOK_URL_HERE")) {
            TextChannel ch = getChatChannel();
            if (ch != null) ch.sendMessage("**" + name + "** > " + EmojiResolver.resolve(sanitize(message), ch.getJDA())).queue();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String content = EmojiResolver.resolve(sanitize(message), jda);
                String json = "{\"username\":\"" + esc(name) + "\",\"avatar_url\":\"https://mc-heads.net/avatar/" + name + "/128\",\"content\":\"" + esc(content) + "\"}";
                sendWebhook(webhookUrl, json);
            } catch (Exception ignored) {}
        });
    }

    private void sendWebhook(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(5000);
        try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
        c.getResponseCode();
        c.disconnect();
    }

    private String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private String sanitize(String s) { return s.replace("@everyone", "@ everyone").replace("@here", "@ here"); }

    // ==================== JOIN / LEAVE ====================

    public void sendJoinLeaveMessage(String name, boolean join, int online, int max) {
        TextChannel ch = getChatChannel();
        if (ch == null) return;
        String tpl = lang(join ? "player-join" : "player-leave");
        if (tpl.isEmpty()) tpl = join ? "**%player%** joined" : "**%player%** left";
        String msg = tpl.replace("%player%", name).replace("%online%", String.valueOf(online)).replace("%max%", String.valueOf(max));

        String footerTpl = lang("online-footer");
        if (footerTpl.isEmpty()) footerTpl = "Online: %online%/%max%";
        String footer = footerTpl.replace("%online%", String.valueOf(online)).replace("%max%", String.valueOf(max));

        msg = EmojiResolver.resolve(msg, ch.getJDA());

        EmbedBuilder e = new EmbedBuilder().setThumbnail("https://mc-heads.net/avatar/" + name + "/64")
                .setColor(join ? new Color(0, 200, 0) : new Color(255, 80, 80)).setDescription(msg)
                .setFooter(footer).setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== DEATH ====================

    public void sendDeathMessage(String name, String death) {
        TextChannel ch = getChatChannel();
        if (ch == null) return;
        String tpl = lang("player-death");
        if (tpl.isEmpty()) tpl = ":skull: %message%";
        String deathMsg = EmojiResolver.resolve(tpl.replace("%player%", name).replace("%message%", death), ch.getJDA());
        EmbedBuilder e = new EmbedBuilder().setColor(new Color(50, 50, 50))
                .setDescription(deathMsg).setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== ADVANCEMENT ====================

    public void sendAdvancementMessage(String name, String adv) {
        TextChannel ch = getChatChannel();
        if (ch == null) return;
        String clean = adv.replaceAll(".*/", "").replace("_", " ");
        StringBuilder fmt = new StringBuilder();
        for (String w : clean.split(" ")) if (!w.isEmpty()) fmt.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        String tpl = lang("player-advancement");
        if (tpl.isEmpty()) tpl = ":trophy: **%player%** made advancement **%advancement%**";
        String advMsg = EmojiResolver.resolve(tpl.replace("%player%", name).replace("%advancement%", fmt.toString().trim()), ch.getJDA());
        EmbedBuilder e = new EmbedBuilder().setColor(new Color(255, 215, 0))
                .setDescription(advMsg).setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== DISCORD -> MC ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent ev) {
        if (ev.getAuthor().isBot() || ev.isWebhookMessage()) return;
        String msg = ev.getMessage().getContentRaw().trim();
        if (msg.isEmpty()) return;
        String msgL = msg.toLowerCase();
        String author = ev.getMember() != null ? ev.getMember().getEffectiveName() : ev.getAuthor().getName();

        // IP command - any channel
        if (msgL.equals("ip")) {
            List<String> ip = plugin.getConfigManager().getIpResponse();
            if (ip != null && !ip.isEmpty()) {
                String title = lang("ip-title");
                if (title.isEmpty()) title = "Server Information";

                List<String> resolved = new ArrayList<>();
                for (String line : ip) resolved.add(EmojiResolver.resolve(line, jda));

                EmbedBuilder e = new EmbedBuilder().setTitle(EmojiResolver.resolve(title, jda))
                        .setColor(new Color(0, 170, 255))
                        .setDescription(String.join("\n", resolved)).setTimestamp(Instant.now());
                ev.getChannel().sendMessageEmbeds(e.build()).queue();
            }
            return;
        }

        // Online command - any channel
        if (msgL.equals("playerlist") || msgL.equals("online")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();

                String title = lang("online-title");
                String noPlayers = lang("online-no-players");
                String footerTpl = lang("online-footer");
                if (title.isEmpty()) title = "Online Players";
                if (noPlayers.isEmpty()) noPlayers = "No players online.";
                if (footerTpl.isEmpty()) footerTpl = "Online: %online%/%max%";

                String footer = footerTpl.replace("%online%", String.valueOf(players.size())).replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));

                EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(0, 200, 100))
                        .setDescription(players.isEmpty() ? noPlayers : players.stream().map(Player::getName).collect(Collectors.joining(", ")))
                        .setFooter(footer).setTimestamp(Instant.now());
                ev.getChannel().sendMessageEmbeds(e.build()).queue();
            });
            return;
        }

        // Chat relay - mc-chat channel only
        String chatChId = plugin.getConfigManager().getDiscordChatConfig().getString("mc-chat-channel-id", "");
        if (ev.getChannel().getId().equals(chatChId)) {
            String fmt = lang("discord-to-mc");
            if (fmt.isEmpty()) fmt = "&9[Discord] &b%player% &f> &f%message%";
            String finalMsg = ChatColor.translateAlternateColorCodes('&', fmt.replace("%player%", author).replace("%message%", msg));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(finalMsg));
        }
    }
}
