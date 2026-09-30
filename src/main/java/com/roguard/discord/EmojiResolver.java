package com.roguard.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts :shortcode: text into usable emoji.
 * - Custom guild emojis  :cup:   -> <:cup:123456789>
 * - Common unicode emoji :skull: -> unicode character (works inside embeds)
 */
public class EmojiResolver {

    private static final Pattern SHORTCODE = Pattern.compile("(?<![a-zA-Z0-9_]):([a-zA-Z0-9_]{2,32}):");
    private static final Map<String, String> UNICODE_MAP = new HashMap<>();
    private static final Map<String, String> CACHE = new HashMap<>();

    static {
        UNICODE_MAP.put("skull", "\uD83D\uDC80");
        UNICODE_MAP.put("skull_crossbones", "\u2620\uFE0F");
        UNICODE_MAP.put("trophy", "\uD83C\uDFC6");
        UNICODE_MAP.put("green_circle", "\uD83D\uDFE2");
        UNICODE_MAP.put("red_circle", "\uD83D\uDD34");
        UNICODE_MAP.put("green_circle", "\uD83D\uDFE2");
        UNICODE_MAP.put("ghost", "\uD83D\uDC7B");
        UNICODE_MAP.put("globe_with_meridians", "\uD83C\uDF10");
        UNICODE_MAP.put("video_game", "\uD83C\uDFAE");
        UNICODE_MAP.put("crossed_swords", "\u2694\uFE0F");
        UNICODE_MAP.put("shield", "\uD83D\uDEE1\uFE0F");
        UNICODE_MAP.put("lock", "\uD83D\uDD12");
        UNICODE_MAP.put("unlock", "\uD83D\uDD13");
        UNICODE_MAP.put("key", "\uD83D\uDD11");
        UNICODE_MAP.put("warning", "\u26A0\uFE0F");
        UNICODE_MAP.put("x", "\u274C");
        UNICODE_MAP.put("white_check_mark", "\u2705");
        UNICODE_MAP.put("no_entry", "\u26D4");
        UNICODE_MAP.put("eyes", "\uD83D\uDC40");
        UNICODE_MAP.put("fire", "\uD83D\uDD25");
        UNICODE_MAP.put("star", "\u2B50");
        UNICODE_MAP.put("sparkles", "\u2728");
        UNICODE_MAP.put("zap", "\u26A1");
        UNICODE_MAP.put("gem", "\uD83D\uDC8E");
        UNICODE_MAP.put("moneybag", "\uD83D\uDCB0");
        UNICODE_MAP.put("coin", "\uD83E\uDE99");
        UNICODE_MAP.put("wave", "\uD83D\uDC4B");
        UNICODE_MAP.put("door", "\uD83D\uDEAA");
        UNICODE_MAP.put("house", "\uD83C\uDFE0");
        UNICODE_MAP.put("crown", "\uD83D\uDC51");
        UNICODE_MAP.put("gem", "\uD83D\uDC8E");
        UNICODE_MAP.put("books", "\uD83D\uDCDA");
        UNICODE_MAP.put("clipboard", "\uD83D\uDCCB");
        UNICODE_MAP.put("hammer", "\uD83D\uDD28");
        UNICODE_MAP.put("tools", "\uD83D\uDEE0\uFE0F");
        UNICODE_MAP.put("gear", "\u2699\uFE0F");
        UNICODE_MAP.put("computer", "\uD83D\uDCBB");
        UNICODE_MAP.put("desktop", "\uD83D\uDDA5\uFE0F");
        UNICODE_MAP.put("link", "\uD83D\uDD17");
        UNICODE_MAP.put("chains", "\u26D3\uFE0F");
        UNICODE_MAP.put("rotating_light", "\uD83D\uDEA8");
        UNICODE_MAP.put("bell", "\uD83D\uDD14");
        UNICODE_MAP.put("loudspeaker", "\uD83D\uDCE2");
        UNICODE_MAP.put("speech_balloon", "\uD83D\uDCAC");
        UNICODE_MAP.put("envelope", "\u2709\uFE0F");
        UNICODE_MAP.put("mailbox", "\uD83D\uDCEB");
        UNICODE_MAP.put("inbox_tray", "\uD83D\uDCE5");
        UNICODE_MAP.put("outbox_tray", "\uD83D\uDCE4");
        UNICODE_MAP.put("arrow_right", "\u27A1\uFE0F");
        UNICODE_MAP.put("arrow_left", "\u2B05\uFE0F");
        UNICODE_MAP.put("arrow_up", "\u2B06\uFE0F");
        UNICODE_MAP.put("arrow_down", "\u2B07\uFE0F");
        UNICODE_MAP.put("checkered_flag", "\uD83C\uDFC1");
        UNICODE_MAP.put("triangular_flag_on_post", "\uD83D\uDEA9");
        UNICODE_MAP.put("flag_white", "\uD83C\uDFF3\uFE0F");
        UNICODE_MAP.put("bangbang", "\u203C\uFE0F");
        UNICODE_MAP.put("question", "\u2753");
        UNICODE_MAP.put("exclamation", "\u2757");
        UNICODE_MAP.put("grey_question", "\u2754");
        UNICODE_MAP.put("heart", "\u2764\uFE0F");
        UNICODE_MAP.put("broken_heart", "\uD83D\uDC94");
        UNICODE_MAP.put("blue_heart", "\uD83D\uDC99");
        UNICODE_MAP.put("diamond_shape_with_a_dot_inside", "\uD83D\uDCA0");
        UNICODE_MAP.put("small_blue_diamond", "\uD83D\uDD39");
        UNICODE_MAP.put("small_red_triangle", "\uD83D\uDD3A");
        UNICODE_MAP.put("small_red_triangle_down", "\uD83D\uDD3B");
        UNICODE_MAP.put("white_circle", "\u26AA");
        UNICODE_MAP.put("black_circle", "\u26AB");
        UNICODE_MAP.put("white_square", "\u2B1C");
        UNICODE_MAP.put("black_square", "\u2B1B");
    }

    /**
     * Resolve every :shortcode: in the text.
     * Priority: custom guild emoji > unicode map > keep original.
     */
    public static String resolve(String text, JDA jda) {
        if (text == null || text.isEmpty()) return text;

        Matcher m = SHORTCODE.matcher(text);
        StringBuffer out = new StringBuffer();

        while (m.find()) {
            String name = m.group(1);

            String replacement = findCustomEmoji(jda, name);
            if (replacement == null) {
                replacement = UNICODE_MAP.getOrDefault(name, m.group(0));
            }

            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        m.appendTail(out);
        return out.toString();
    }

    private static String findCustomEmoji(JDA jda, String name) {
        if (jda == null) return null;

        String cacheKey = name.toLowerCase();
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        for (Guild guild : jda.getGuilds()) {
            try {
                List<RichCustomEmoji> emojis = guild.getEmojisByName(name, true);
                if (!emojis.isEmpty()) {
                    RichCustomEmoji e = emojis.get(0);
                    String formatted = (e.isAnimated() ? "<a:" : "<:") + e.getName() + ":" + e.getId() + ">";
                    CACHE.put(cacheKey, formatted);
                    return formatted;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    /** Rebuild the custom-emoji cache (call on bot start or config reload). */
    public static void clearCache() {
        CACHE.clear();
    }
}
