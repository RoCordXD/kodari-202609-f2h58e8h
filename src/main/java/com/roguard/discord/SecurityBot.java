package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.logging.SensitiveLogFilter;
import com.roguard.manager.VerifyManager;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class SecurityBot extends ListenerAdapter {

    private final RoGuard plugin;
    private final String token;
    private JDA jda;
    private final List<String> consoleBuffer = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public SecurityBot(RoGuard plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start() throws Exception {
        JDALogger.setFallbackLoggerEnabled(false);
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_EMOJIS_AND_STICKERS)
                .enableCache(CacheFlag.EMOJI)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .addEventListeners(this).build();
        jda.awaitReady();
        if (plugin.getConfigManager().isConsoleMirrorEnabled())
            scheduler.scheduleAtFixedRate(this::flushConsoleBuffer, 2, 2, TimeUnit.SECONDS);
    }

    public void shutdown() {
        flushConsoleBuffer();
        scheduler.shutdown();
        if (jda != null) jda.shutdown();
    }

    public JDA getJDA() { return jda; }

    private String lang(String key) {
        return plugin.getLanguageManager().getDiscord(key);
    }

    // ==================== COMMAND VERIFICATION ====================

    public void sendVerifyRequest(String id, String sender, String cmd, boolean console) {
        if (jda == null || !plugin.getConfigManager().isCommandVerificationEnabled()) return;
        sendVerifyToChannel(id, console ? "CONSOLE" : sender, cmd);
        if (!console) {
            String did = plugin.getConfigManager().getStaffDiscordId(sender);
            if (did != null && !did.isEmpty() && !did.equals("000000000000000000"))
                sendVerifyDM(id, sender, cmd, did);
        }
    }

    private void sendVerifyToChannel(String id, String sender, String cmd) {
        String chId = plugin.getConfigManager().getProtectConfig().getString("verify-history-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;

        String title = lang("verify-title");
        if (title.isEmpty()) title = "Command Verification";
        String desc = lang("verify-description");

        EmbedBuilder e = new EmbedBuilder()
                .setTitle(EmojiResolver.resolve(title, ch.getJDA()))
                .setColor(new Color(255, 165, 0))
                .addField("Sender", sender, true).addField("Command", "`" + cmd + "`", true)
                .addField("ID", id, true).setTimestamp(Instant.now());
        if (!desc.isEmpty()) e.setDescription(EmojiResolver.resolve(desc, ch.getJDA()));

        String btnApprove = lang("btn-approve");
        String btnDeny = lang("btn-deny");
        if (btnApprove.isEmpty()) btnApprove = "Approve";
        if (btnDeny.isEmpty()) btnDeny = "Deny";

        ch.sendMessageEmbeds(e.build()).setActionRow(
                Button.success("cv_a_" + id, btnApprove),
                Button.danger("cv_d_" + id, btnDeny)).queue();
    }

    private void sendVerifyDM(String id, String sender, String cmd, String did) {
        jda.retrieveUserById(did).queue(u -> {
            if (u == null) return;

            String title = lang("verify-title");
            if (title.isEmpty()) title = "Command Verification";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(255, 165, 0))
                    .addField("Sender", sender, true).addField("Command", "`" + cmd + "`", true)
                    .setTimestamp(Instant.now());

            String btnApprove = lang("btn-approve");
            String btnDeny = lang("btn-deny");
            if (btnApprove.isEmpty()) btnApprove = "Approve";
            if (btnDeny.isEmpty()) btnDeny = "Deny";

            final String ba = btnApprove, bd = btnDeny;
            u.openPrivateChannel().queue(c -> c.sendMessageEmbeds(e.build())
                    .setActionRow(Button.success("cv_a_" + id, ba), Button.danger("cv_d_" + id, bd))
                    .queue(s -> {}, er -> {}), er -> {});
        }, er -> {});
    }

    // ==================== COMMAND LOG ====================

    public void logCommand(String sender, String cmd) {
        if (jda == null || !plugin.getConfigManager().isCommandLogEnabled()) return;
        String chId = plugin.getConfigManager().getProtectConfig().getString("command-log-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;

        String pLabel = lang("cmd-log-player-label");
        String cLabel = lang("cmd-log-command-label");
        if (pLabel.isEmpty()) pLabel = "Player";
        if (cLabel.isEmpty()) cLabel = "Command";

        EmbedBuilder e = new EmbedBuilder().setColor(new Color(100, 100, 255))
                .addField(EmojiResolver.resolve(pLabel, ch.getJDA()), sender, true)
                .addField(EmojiResolver.resolve(cLabel, ch.getJDA()), "`" + cmd + "`", true)
                .setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== VPN LOG ====================

    public void logVPNDetection(String name, String ip, String reason) {
        if (jda == null) return;
        String chId = plugin.getConfigManager().getProtectConfig().getString("ip-log-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;

        String title = lang("vpn-title");
        String pLabel = lang("vpn-player-label");
        String iLabel = lang("vpn-ip-label");
        String rLabel = lang("vpn-reason-label");
        if (title.isEmpty()) title = "VPN/Proxy Detected";
        if (pLabel.isEmpty()) pLabel = "Player";
        if (iLabel.isEmpty()) iLabel = "IP";
        if (rLabel.isEmpty()) rLabel = "Reason";

        EmbedBuilder e = new EmbedBuilder()
                .setTitle(EmojiResolver.resolve(title, ch.getJDA()))
                .setColor(new Color(255, 100, 100))
                .addField(pLabel, name, true).addField(iLabel, "`" + ip + "`", true)
                .addField(rLabel, reason, true).setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== STAFF 2FA ====================

    public void sendStaff2FAVerify(String did, String name, String id) {
        if (jda == null || !plugin.getConfigManager().isStaff2FAEnabled()) return;
        jda.retrieveUserById(did).queue(u -> {
            if (u == null) return;

            String title = lang("staff-2fa-title");
            String desc = lang("staff-2fa-description");
            if (title.isEmpty()) title = "Staff 2FA Verification";
            if (desc.isEmpty()) desc = "Login detected.";

            String btnV = lang("btn-verify");
            String btnD = lang("btn-deny-2fa");
            if (btnV.isEmpty()) btnV = "Verify";
            if (btnD.isEmpty()) btnD = "Deny (Not Me!)";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(0, 200, 100))
                    .setDescription(desc).addField("Name", name, true).setTimestamp(Instant.now());

            final String bv = btnV, bd = btnD;
            u.openPrivateChannel().queue(c -> c.sendMessageEmbeds(e.build())
                    .setActionRow(Button.success("s2fa_v_" + id, bv), Button.danger("s2fa_d_" + id, bd))
                    .queue(s -> {}, er -> {}), er -> {});
        }, er -> {});
    }

    // ==================== BUTTON INTERACTIONS ====================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent ev) {
        String bid = ev.getComponentId();

        // Command Verify Approve
        if (bid.startsWith("cv_a_")) {
            String id = bid.substring(5);
            VerifyManager.PendingCommand p = plugin.getVerifyManager().removePendingCommand(id);
            if (p == null) {
                String msg = lang("verify-expired-msg");
                ev.reply(msg.isEmpty() ? "Expired." : msg).setEphemeral(true).queue();
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isConsole()) {
                    String c = p.getCommand().startsWith("/") ? p.getCommand().substring(1) : p.getCommand();
                    plugin.addVerifiedCommand("console:" + c);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                } else {
                    Player pl = Bukkit.getPlayer(p.getSender());
                    if (pl != null && pl.isOnline()) {
                        // Shopkeeper edit: grant timed access instead of running a command
                        if (p.getCommand().startsWith("Shopkeeper Edit:")) {
                            plugin.grantShopkeeperBypass(pl.getUniqueId());
                            pl.sendMessage(plugin.getLanguageManager().msg("security.shopkeeper-approved"));
                            return;
                        }
                        String c = p.getCommand().startsWith("/") ? p.getCommand().substring(1) : p.getCommand();
                        plugin.addVerifiedCommand(pl.getName() + ":" + c);
                        // Allow gamemode change if this is a gamemode command
                        plugin.allowGameModeChange(pl.getUniqueId());
                        pl.performCommand(c);
                        pl.sendMessage(plugin.getLanguageManager().msg("security.command-approved"));
                    }
                }
            });

            String title = lang("verify-approved-title");
            if (title.isEmpty()) title = "Approved";

            EmbedBuilder e = new EmbedBuilder()
                    .setTitle(EmojiResolver.resolve(title, jda)).setColor(new Color(0, 200, 0))
                    .addField("Sender", p.getSender(), true).addField("Command", "`" + p.getCommand() + "`", true)
                    .setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.success("x", title).asDisabled()).queue();
        }

        // Command Verify Deny
        if (bid.startsWith("cv_d_")) {
            String id = bid.substring(5);
            VerifyManager.PendingCommand p = plugin.getVerifyManager().removePendingCommand(id);
            if (p == null) {
                String msg = lang("verify-expired-msg");
                ev.reply(msg.isEmpty() ? "Expired." : msg).setEphemeral(true).queue();
                return;
            }
            if (!p.isConsole()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player pl = Bukkit.getPlayer(p.getSender());
                    if (pl != null) pl.sendMessage(plugin.getLanguageManager().msg("security.command-denied"));
                });
            }

            String title = lang("verify-denied-title");
            if (title.isEmpty()) title = "Denied";

            EmbedBuilder e = new EmbedBuilder()
                    .setTitle(EmojiResolver.resolve(title, jda)).setColor(new Color(255, 0, 0))
                    .addField("Sender", p.getSender(), true).addField("Command", "`" + p.getCommand() + "`", true)
                    .setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.danger("x", title).asDisabled()).queue();
        }

        // Staff 2FA Verify
        if (bid.startsWith("s2fa_v_")) {
            String id = bid.substring(7);
            UUID uuid = plugin.getTwoFactorManager().removeStaff2FARequest(id);
            if (uuid == null) { ev.reply("Expired.").setEphemeral(true).queue(); return; }
            plugin.getTwoFactorManager().unfreezeStaff(uuid);

            String title = lang("staff-2fa-verified-title");
            if (title.isEmpty()) title = "Verified";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(0, 200, 0)).setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.success("x", title).asDisabled()).queue();
        }

        // Staff 2FA Deny
        if (bid.startsWith("s2fa_d_")) {
            String id = bid.substring(7);
            UUID uuid = plugin.getTwoFactorManager().removeStaff2FARequest(id);
            if (uuid == null) { ev.reply("Expired.").setEphemeral(true).queue(); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pl = Bukkit.getPlayer(uuid);
                if (pl != null) pl.kickPlayer(plugin.getLanguageManager().get("2fa.verify-denied"));
            });

            String title = lang("staff-2fa-denied-title");
            if (title.isEmpty()) title = "Denied";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(255, 0, 0)).setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.danger("x", title).asDisabled()).queue();
        }
    }

    // ==================== CONSOLE CHANNEL ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent ev) {
        if (ev.getAuthor().isBot() || !plugin.getConfigManager().isConsoleMirrorEnabled()) return;

        String chId = plugin.getConfigManager().getProtectConfig().getString("console-channel-id", "");
        if (chId == null || chId.isEmpty() || !ev.getChannel().getId().equals(chId)) return;

        // Ignore our own log output pasted as commands (lines starting with [ or `)
        String raw = ev.getMessage().getContentRaw().trim();
        if (raw.isEmpty()) return;
        if (raw.startsWith("[") || raw.startsWith("```")) return;

        // Strip leading slash — dispatchCommand does not accept "/cmd"
        String cmd = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (cmd.isEmpty()) return;

        // Optional whitelist: only allow configured Discord users to run commands
        List<String> allowedUsers = plugin.getConfigManager().getConsoleAllowedUserIds();
        if (allowedUsers != null && !allowedUsers.isEmpty()) {
            boolean allowed = false;
            for (String id : allowedUsers) {
                if (id.trim().equals(ev.getAuthor().getId())) { allowed = true; break; }
            }
            if (!allowed) {
                ev.getChannel().sendMessage(":no_entry: You are not allowed to use the console.").queue();
                return;
            }
        }

        String execLabel = lang("console-executed");
        String pendLabel = lang("console-pending");
        if (execLabel.isEmpty()) execLabel = "[Executed]";
        if (pendLabel.isEmpty()) pendLabel = "[Pending]";

        final String el = execLabel, pl = pendLabel, finalCmd = cmd;
        final String senderName = "Discord:" + ev.getAuthor().getName();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean restricted = plugin.getConfigManager().isCommandVerificationEnabled()
                        && plugin.getConfigManager().isCommandRestricted(finalCmd);

                if (restricted) {
                    // Needs verification — never auto-execute
                    String id = plugin.getVerifyManager().addPendingCommand(senderName, "/" + finalCmd, true);
                    sendVerifyRequest(id, senderName, "/" + finalCmd, true);
                    ev.getChannel().sendMessage("`" + pl + " /" + finalCmd + "`").queue();
                } else {
                    // Register the exact key ConsoleCommandListener looks for
                    plugin.addVerifiedCommand("console:" + finalCmd);
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    if (success) {
                        ev.getChannel().sendMessage("`" + el + " /" + finalCmd + "`").queue();
                    } else {
                        ev.getChannel().sendMessage(":warning: Unknown command: `/" + finalCmd + "`").queue();
                    }
                }
            } catch (Exception ex) {
                ev.getChannel().sendMessage(":x: Error: `" + ex.getMessage() + "`").queue();
                plugin.getLogger().warning("Discord console command failed: " + ex.getMessage());
            }
        });
    }

    /** Log blocked bots to the IP log channel. */
    public void logAntiBot(String name, String ip, String reason) {
        if (jda == null) return;
        String chId = plugin.getConfigManager().getProtectConfig().getString("ip-log-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;

        EmbedBuilder e = new EmbedBuilder()
                .setTitle(":robot: Bot Blocked")
                .setColor(new Color(255, 80, 80))
                .addField("Player", name == null ? "?" : name, true)
                .addField("IP", ip == null ? "?" : "`" + ip + "`", true)
                .addField("Reason", reason, true)
                .setTimestamp(Instant.now());
        ch.sendMessageEmbeds(e.build()).queue();
    }

    // ==================== CONSOLE BUFFER ====================

    public void sendConsoleMessage(String msg) {
        if (jda == null || !plugin.getConfigManager().isConsoleMirrorEnabled() || msg == null) return;
        if (msg.contains("SLF4J") || msg.contains("moved too quickly")) return;

        if (SensitiveLogFilter.containsExposedPassword(msg)) return;

        String clean = msg.replaceAll("\u001B\\[[;\\d]*m", "").replaceAll("^\\[\\d{2}:\\d{2}:\\d{2}.*?\\]\\s*", "").trim();
        if (clean.isEmpty() || clean.length() > 200) return;
        synchronized (consoleBuffer) { consoleBuffer.add(clean); if (consoleBuffer.size() >= 15) flushConsoleBuffer(); }
    }

    private void flushConsoleBuffer() {
        if (jda == null) return;
        List<String> toSend;
        synchronized (consoleBuffer) { if (consoleBuffer.isEmpty()) return; toSend = new ArrayList<>(consoleBuffer); consoleBuffer.clear(); }
        String chId = plugin.getConfigManager().getProtectConfig().getString("console-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;
        StringBuilder sb = new StringBuilder("```\n");
        for (String s : toSend) sb.append(s).append("\n");
        sb.append("```");
        String m = sb.length() > 1990 ? sb.substring(0, 1987) + "```" : sb.toString();
        ch.sendMessage(m).queue();
    }
}
