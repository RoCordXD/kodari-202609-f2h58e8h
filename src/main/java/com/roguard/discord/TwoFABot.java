package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
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
import java.util.UUID;

public class TwoFABot extends ListenerAdapter {

    private final RoGuard plugin;
    private final String token;
    private JDA jda;

    public TwoFABot(RoGuard plugin, String token) {
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
    }

    public void shutdown() { if (jda != null) jda.shutdown(); }
    public JDA getJDA() { return jda; }

    private String lang(String key) {
        return plugin.getLanguageManager().getDiscord(key);
    }

    // ==================== LINK CODE ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String linkChId = plugin.getConfigManager().get2FAConfig().getString("link-channel-id", "");
        if (!event.getChannel().getId().equals(linkChId)) return;

        String code = event.getMessage().getContentRaw().trim();

        if (!code.matches("\\d{6}")) {
            event.getMessage().delete().queue(s -> {}, e -> {});
            return;
        }

        UUID playerUUID = plugin.getTwoFactorManager().verifyLinkCode(code);
        if (playerUUID == null) {
            String msg = lang("link-invalid-code");
            if (msg.isEmpty()) msg = "Invalid or expired code!";
            event.getMessage().reply(msg).queue(m -> m.delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS));
            event.getMessage().delete().queue(s -> {}, e -> {});
            return;
        }

        String discordId = event.getAuthor().getId();
        String discordName = event.getAuthor().getName();

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getData(playerUUID);
        data.setDiscordId(discordId);
        data.setLinked(true);
        plugin.getPlayerDataManager().saveData(playerUUID);

        // Delete the code message for security
        event.getMessage().delete().queue(s -> {}, e -> {});

        // Send confirmation to user's DM instead of the channel
        event.getAuthor().openPrivateChannel().queue(dm -> {
            String title = lang("link-success-title");
            if (title.isEmpty()) title = "Account Linked!";

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(EmojiResolver.resolve(title, event.getJDA()))
                    .setColor(new Color(0, 200, 0))
                    .addField("Minecraft", data.getLastName(), true)
                    .addField("Discord", discordName, true)
                    .setTimestamp(Instant.now());

            dm.sendMessageEmbeds(embed.build()).queue(s -> {}, e -> {});
        }, e -> {});

        logLink(data.getLastName(), discordName, discordId);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.kickPlayer(plugin.getLanguageManager().get("2fa.link-kick"));
            }
        });
    }

    private void logLink(String mcName, String discordName, String discordId) {
        String chId = plugin.getConfigManager().get2FAConfig().getString("link-log-channel-id", "");
        TextChannel ch = jda.getTextChannelById(chId);
        if (ch == null) return;

        String title = lang("link-log-title");
        if (title.isEmpty()) title = "New Account Link";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(EmojiResolver.resolve(title, ch.getJDA()))
                .setColor(new Color(0, 170, 255))
                .addField("Minecraft", mcName, true)
                .addField("Discord", discordName + "\n`" + discordId + "`", true)
                .setTimestamp(Instant.now());

        ch.sendMessageEmbeds(embed.build()).queue();
    }

    // ==================== PLAYER 2FA VERIFY ====================

    public void sendPlayer2FAVerify(String discordId, String playerName, String verifyId) {
        if (jda == null) return;

        jda.retrieveUserById(discordId).queue(user -> {
            if (user == null) return;

            String title = lang("player-2fa-title");
            String desc = lang("player-2fa-description");
            if (title.isEmpty()) title = "Login Verification";
            if (desc.isEmpty()) desc = "A login was detected for your linked account.";

            String btnV = lang("btn-verify");
            String btnD = lang("btn-deny-2fa");
            if (btnV.isEmpty()) btnV = "Verify";
            if (btnD.isEmpty()) btnD = "Deny (Not Me!)";

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(EmojiResolver.resolve(title, jda)).setColor(new Color(0, 200, 100))
                    .setDescription(EmojiResolver.resolve(desc, jda))
                    .addField("Minecraft", playerName, true).setTimestamp(Instant.now());

            final String bv = btnV, bd = btnD;
            user.openPrivateChannel().queue(ch ->
                    ch.sendMessageEmbeds(embed.build())
                            .setActionRow(Button.success("p2fa_v_" + verifyId, bv), Button.danger("p2fa_d_" + verifyId, bd))
                            .queue(s -> {}, e -> {}), e -> {});
        }, e -> {});
    }

    // ==================== BUTTON INTERACTIONS ====================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent ev) {
        String bid = ev.getComponentId();

        // Verify
        if (bid.startsWith("p2fa_v_")) {
            String id = bid.substring(7);
            UUID uuid = plugin.getTwoFactorManager().removePlayer2FARequest(id);
            if (uuid == null) { ev.reply("Expired.").setEphemeral(true).queue(); return; }

            plugin.getTwoFactorManager().unfreezePlayer(uuid);

            String title = lang("player-2fa-verified-title");
            String desc = lang("player-2fa-verified-description");
            if (title.isEmpty()) title = "Verified";
            if (desc.isEmpty()) desc = "You can now play.";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(0, 200, 0))
                    .setDescription(desc).setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.success("x", title).asDisabled()).queue();
        }

        // Deny
        if (bid.startsWith("p2fa_d_")) {
            String id = bid.substring(7);
            UUID uuid = plugin.getTwoFactorManager().removePlayer2FARequest(id);
            if (uuid == null) { ev.reply("Expired.").setEphemeral(true).queue(); return; }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.kickPlayer(plugin.getLanguageManager().get("2fa.verify-denied"));
            });

            String title = lang("player-2fa-denied-title");
            String desc = lang("player-2fa-denied-description");
            if (title.isEmpty()) title = "Denied";
            if (desc.isEmpty()) desc = "Player kicked.";

            EmbedBuilder e = new EmbedBuilder().setTitle(title).setColor(new Color(255, 0, 0))
                    .setDescription(desc).setTimestamp(Instant.now());
            ev.editMessageEmbeds(e.build()).setActionRow(Button.danger("x", title).asDisabled()).queue();
        }
    }
}
