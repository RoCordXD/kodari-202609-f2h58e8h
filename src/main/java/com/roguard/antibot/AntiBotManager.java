package com.roguard.antibot;

import com.roguard.RoGuard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Detects and blocks bot attacks.
 * Heuristics: join rate, same-IP accounts, bot-like names, instant rejoin loops.
 */
public class AntiBotManager {

    private final RoGuard plugin;

    // Global join timestamps (millis)
    private final Deque<Long> globalJoins = new ArrayDeque<>();

    // IP -> join timestamps
    private final Map<String, Deque<Long>> ipJoins = new ConcurrentHashMap<>();

    // IP -> account names seen (alt/bot farm detection)
    private final Map<String, Set<String>> ipAccounts = new ConcurrentHashMap<>();

    // Shared prefix -> recent unique random suffixes, e.g. attack-x7Fk and attack-Qp2z.
    private final Map<String, Deque<PrefixAttempt>> prefixAttempts = new ConcurrentHashMap<>();

    // Name pattern typical for bots: word + 3+ digits, or long random consonant strings
    private static final Pattern BOT_NAME_PATTERN = Pattern.compile(
            "^(?:.*\\d{3,})$|" +                    // ends with 3+ digits: Bot001, Steve1234
            "^(?:[a-z]+[A-Z]?\\d{2,})$|" +           // name + digits
            "^(?:(?:x|z|q|w){4,}.*)$|" +             // keyboard smash: xqzww...
            "^(?:[A-Za-z]{1,3}\\d{5,})$"             // short prefix + many digits
    );

    // Very long random-looking names
    private static final int SUSPICIOUS_NAME_LENGTH = 16;

    public AntiBotManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    /** True if the join should be blocked. */
    public synchronized boolean isBot(String name, String ip) {
        if (!plugin.getConfigManager().isAntiBotEnabled()) return false;

        long now = System.currentTimeMillis();

        // 1. Global join flood
        long window = plugin.getConfigManager().getAntiBotWindowSeconds() * 1000L;
        int maxJoins = plugin.getConfigManager().getAntiBotMaxJoins();
        prune(globalJoins, now, window);
        globalJoins.addLast(now);
        if (globalJoins.size() > maxJoins) {
            return true; // attack in progress
        }

        // 2. Same IP join flood
        if (ip != null && !ip.isEmpty()) {
            Deque<Long> ipTimes = ipJoins.computeIfAbsent(ip, k -> new ArrayDeque<>());
            prune(ipTimes, now, window);
            ipTimes.addLast(now);
            if (ipTimes.size() > plugin.getConfigManager().getAntiBotSameIpMaxJoins()) {
                return true;
            }

            // 3. Too many accounts from one IP
            Set<String> accounts = ipAccounts.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet());
            accounts.add(name);
            if (accounts.size() > plugin.getConfigManager().getAntiBotSameIpMaxAccounts()) {
                return true;
            }
        }

        // 4. Bot-like name
        if (plugin.getConfigManager().isAntiBotNameCheckEnabled()) {
            if (isBotName(name)) return true;
        }

        // 5. Repeated prefix + separator + random suffix attack pattern.
        // The first names are observed; blocking starts only at the configured threshold.
        if (plugin.getConfigManager().isAntiBotPrefixPatternEnabled()
                && recordPrefixPattern(name, now)) {
            return true;
        }

        return false;
    }

    /** Human-readable reason for logging. */
    public synchronized String detectReason(String name, String ip) {
        long now = System.currentTimeMillis();
        long window = plugin.getConfigManager().getAntiBotWindowSeconds() * 1000L;

        if (globalJoins.size() > plugin.getConfigManager().getAntiBotMaxJoins()
                && !globalJoins.isEmpty()
                && now - globalJoins.peekFirst() < window) {
            return "Join flood (" + globalJoins.size() + " joins in "
                    + plugin.getConfigManager().getAntiBotWindowSeconds() + "s)";
        }

        if (ip != null && !ip.isEmpty()) {
            Deque<Long> ipTimes = ipJoins.get(ip);
            if (ipTimes != null && ipTimes.size() > plugin.getConfigManager().getAntiBotSameIpMaxJoins()) {
                return "IP join flood (" + ipTimes.size() + " from " + ip + ")";
            }

            Set<String> accounts = ipAccounts.get(ip);
            if (accounts != null && accounts.size() > plugin.getConfigManager().getAntiBotSameIpMaxAccounts()) {
                return "Too many accounts from one IP (" + accounts.size() + ")";
            }
        }

        String prefix = extractPrefix(name);
        if (prefix != null && isPrefixPatternAttack(prefix, now)) {
            return "Shared-prefix bot pattern (" + prefix + "-<random>)";
        }

        if (plugin.getConfigManager().isAntiBotNameCheckEnabled() && isBotName(name)) {
            return "Bot-like name pattern";
        }
        return "Suspicious activity";
    }

    public boolean isBotName(String name) {
        if (name == null || name.isEmpty()) return true;
        if (name.length() >= SUSPICIOUS_NAME_LENGTH && BOT_NAME_PATTERN.matcher(name).matches()) return true;
        return BOT_NAME_PATTERN.matcher(name).matches();
    }

    private boolean recordPrefixPattern(String name, long now) {
        PrefixName parsed = parsePrefixName(name);
        if (parsed == null) return false;

        long window = plugin.getConfigManager().getAntiBotPrefixPatternWindowSeconds() * 1000L;
        Deque<PrefixAttempt> attempts = prefixAttempts.computeIfAbsent(
                parsed.prefix, ignored -> new ArrayDeque<>()
        );

        prunePrefixAttempts(attempts, now, window);

        // Count unique suffixes only. Reconnecting with the same account does not
        // create a false shared-prefix attack.
        boolean suffixAlreadySeen = attempts.stream()
                .anyMatch(attempt -> attempt.suffix.equalsIgnoreCase(parsed.suffix));
        if (!suffixAlreadySeen) {
            attempts.addLast(new PrefixAttempt(now, parsed.suffix));
        }

        return attempts.size() >= plugin.getConfigManager().getAntiBotPrefixPatternThreshold();
    }

    private boolean isPrefixPatternAttack(String prefix, long now) {
        Deque<PrefixAttempt> attempts = prefixAttempts.get(prefix.toLowerCase(Locale.ROOT));
        if (attempts == null) return false;

        long window = plugin.getConfigManager().getAntiBotPrefixPatternWindowSeconds() * 1000L;
        prunePrefixAttempts(attempts, now, window);
        return attempts.size() >= plugin.getConfigManager().getAntiBotPrefixPatternThreshold();
    }

    private String extractPrefix(String name) {
        PrefixName parsed = parsePrefixName(name);
        return parsed == null ? null : parsed.prefix;
    }

    private PrefixName parsePrefixName(String name) {
        if (name == null || name.isEmpty()) return null;

        int separatorIndex = Math.max(name.lastIndexOf('-'), name.lastIndexOf('_'));
        if (separatorIndex <= 0 || separatorIndex >= name.length() - 1) return null;

        String prefix = name.substring(0, separatorIndex);
        String suffix = name.substring(separatorIndex + 1);

        int minPrefix = plugin.getConfigManager().getAntiBotPrefixPatternMinPrefixLength();
        int minSuffix = plugin.getConfigManager().getAntiBotPrefixPatternMinSuffixLength();
        if (prefix.length() < minPrefix || suffix.length() < minSuffix) return null;
        if (!prefix.matches("[A-Za-z0-9]+") || !suffix.matches("[A-Za-z0-9]+")) return null;

        return new PrefixName(prefix.toLowerCase(Locale.ROOT), suffix);
    }

    private void prunePrefixAttempts(Deque<PrefixAttempt> attempts, long now, long window) {
        while (!attempts.isEmpty() && now - attempts.peekFirst().timestamp > window) {
            attempts.pollFirst();
        }
    }

    /** Reset counters (called on reload or after attack cooldown). */
    public void reset() {
        globalJoins.clear();
        ipJoins.clear();
        ipAccounts.clear();
        prefixAttempts.clear();
    }

    /** True while an attack is being mitigated. */
    public synchronized boolean isUnderAttack() {
        long now = System.currentTimeMillis();
        long window = plugin.getConfigManager().getAntiBotWindowSeconds() * 1000L;
        prune(globalJoins, now, window);
        return globalJoins.size() > plugin.getConfigManager().getAntiBotMaxJoins();
    }

    private void prune(Deque<Long> deque, long now, long window) {
        while (!deque.isEmpty() && now - deque.peekFirst() > window) {
            deque.pollFirst();
        }
    }

    private static class PrefixName {
        private final String prefix;
        private final String suffix;

        private PrefixName(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }
    }

    private static class PrefixAttempt {
        private final long timestamp;
        private final String suffix;

        private PrefixAttempt(long timestamp, String suffix) {
            this.timestamp = timestamp;
            this.suffix = suffix;
        }
    }
}
