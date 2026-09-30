package com.roguard.dialog;

import com.roguard.RoGuard;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds the player manager dialogs (list, stats, inventory entry point). */
public final class PlayerManagerDialog {

    public static final String IN_SEARCH = "search";
    public static final String IN_REASON = "reason";
    public static final String IN_DURATION = "duration";

    /** Max player buttons shown on one page. */
    public static final int PAGE_SIZE = 20;

    private final RoGuard plugin;

    public PlayerManagerDialog(RoGuard plugin) {
        this.plugin = plugin;
    }

    private Component lang(String key, String fallback) {
        String raw = plugin.getLanguageManager().get("playermanager." + key);
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) {
            return Component.text(fallback);
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    private String langRaw(String key, String fallback) {
        String raw = plugin.getLanguageManager().get("playermanager." + key);
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) return fallback;
        return raw;
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    // ==================== PLAYER LIST ====================

    /** Collects every player that has joined before, online players first. */
    public List<OfflinePlayer> collectPlayers(String filter) {
        List<OfflinePlayer> result = new ArrayList<>();

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name == null) continue;
            if (filter != null && !filter.isBlank()
                    && !name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(offline);
        }

        // Online players may be missing from the cache on some setups.
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean present = result.stream()
                    .anyMatch(p -> p.getUniqueId().equals(online.getUniqueId()));
            if (present) continue;

            String name = online.getName();
            if (filter != null && !filter.isBlank()
                    && !name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(online);
        }

        result.sort(Comparator
                .comparing((OfflinePlayer p) -> !p.isOnline())
                .thenComparing(p -> p.getName() == null ? "" : p.getName().toLowerCase(Locale.ROOT)));

        return result;
    }

    public Dialog createListDialog(String filter, int page) {
        List<OfflinePlayer> players = collectPlayers(filter);

        int totalPages = Math.max(1, (int) Math.ceil(players.size() / (double) PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, players.size());

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            OfflinePlayer target = players.get(i);
            String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
            String prefix = target.isOnline()
                    ? langRaw("online-prefix", "&a● ")
                    : langRaw("offline-prefix", "&7● ");

            buttons.add(ActionButton.builder(legacy(prefix + name))
                    .tooltip(lang("open-player-tooltip", "Click to manage this player"))
                    .width(150)
                    .action(DialogAction.customClick(
                            DialogKeys.playerView(target.getUniqueId()), null))
                    .build());
        }

        if (buttons.isEmpty()) {
            buttons.add(ActionButton.builder(lang("no-results", "No players found"))
                    .width(200)
                    .build());
        }

        // Search re-runs the list with the typed filter.
        buttons.add(ActionButton.builder(lang("search-button", "Search"))
                .tooltip(lang("search-button-tooltip", "Filter the list by the name above"))
                .width(150)
                .action(DialogAction.customClick(DialogKeys.PM_SEARCH, null))
                .build());

        if (safePage > 0) {
            buttons.add(ActionButton.builder(lang("previous-page", "Previous"))
                    .width(100)
                    .action(DialogAction.customClick(DialogKeys.page(safePage - 1), null))
                    .build());
        }
        if (safePage < totalPages - 1) {
            buttons.add(ActionButton.builder(lang("next-page", "Next"))
                    .width(100)
                    .action(DialogAction.customClick(DialogKeys.page(safePage + 1), null))
                    .build());
        }

        String header = langRaw("list-info", "&7Players: &f%count%  &7Page: &f%page%/%total%")
                .replace("%count%", String.valueOf(players.size()))
                .replace("%page%", String.valueOf(safePage + 1))
                .replace("%total%", String.valueOf(totalPages));

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(lang("list-title", "Player Manager"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(legacy(header))))
                        .inputs(List.of(
                                DialogInput.text(IN_SEARCH, lang("search-label", "Search name"))
                                        .width(300)
                                        .maxLength(32)
                                        .initial(filter == null ? "" : filter)
                                        .build()
                        ))
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(2)
                        .build())
        );
    }

    // ==================== PLAYER DETAIL ====================

    public Dialog createPlayerDialog(OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        List<ActionButton> buttons = new ArrayList<>();

        // Stat buttons carry no action, so clicking them simply dismisses the dialog.
        buttons.add(statButton("playtime", "Playtime: %value%", formatPlaytime(target)));
        buttons.add(statButton("kills", "Player Kills: %value%",
                String.valueOf(safeStat(target, Statistic.PLAYER_KILLS))));
        buttons.add(statButton("deaths", "Deaths: %value%",
                String.valueOf(safeStat(target, Statistic.DEATHS))));
        buttons.add(statButton("mined", "Blocks Mined: %value%",
                String.valueOf(totalMined(target))));
        buttons.add(statButton("mobkills", "Mob Kills: %value%",
                String.valueOf(safeStat(target, Statistic.MOB_KILLS))));

        UUID id = target.getUniqueId();

        buttons.add(ActionButton.builder(lang("open-inventory", "Open Inventory"))
                .tooltip(lang("open-inventory-tooltip", "Edit this player's inventory"))
                .width(200)
                .action(DialogAction.customClick(DialogKeys.inventory(id), null))
                .build());

        buttons.add(ActionButton.builder(lang("open-enderchest", "Open Ender Chest"))
                .tooltip(lang("open-enderchest-tooltip", "Edit this player's ender chest"))
                .width(200)
                .action(DialogAction.customClick(DialogKeys.enderchest(id), null))
                .build());

        buttons.add(ActionButton.builder(lang("clear-inventory", "Clear Inventory"))
                .tooltip(lang("clear-inventory-tooltip", "Delete every item this player carries"))
                .width(200)
                .action(DialogAction.customClick(DialogKeys.formClear(id), null))
                .build());

        // Punishment actions
        boolean banned = plugin.getPunishmentManager().isBanned(target);
        boolean muted = plugin.getPunishmentManager().isMuted(id);

        if (banned) {
            buttons.add(ActionButton.builder(lang("unban", "Unban"))
                    .tooltip(lang("unban-tooltip", "Remove the ban from this player"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.unban(id), null))
                    .build());
        } else {
            buttons.add(ActionButton.builder(lang("ban", "Ban"))
                    .tooltip(lang("ban-tooltip", "Permanently ban this player"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formBan(id), null))
                    .build());
            buttons.add(ActionButton.builder(lang("tempban", "Temp Ban"))
                    .tooltip(lang("tempban-tooltip", "Ban this player for a set time"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formTempban(id), null))
                    .build());
            buttons.add(ActionButton.builder(lang("banip", "Ban IP"))
                    .tooltip(lang("banip-tooltip", "Permanently ban this player's IP"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formBanip(id), null))
                    .build());
            buttons.add(ActionButton.builder(lang("tempbanip", "Temp Ban IP"))
                    .tooltip(lang("tempbanip-tooltip", "Ban this player's IP for a set time"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formTempbanip(id), null))
                    .build());
        }

        if (muted) {
            buttons.add(ActionButton.builder(lang("unmute", "Unmute"))
                    .tooltip(lang("unmute-tooltip", "Remove the mute from this player"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.unmute(id), null))
                    .build());
        } else {
            buttons.add(ActionButton.builder(lang("mute", "Mute"))
                    .tooltip(lang("mute-tooltip", "Permanently mute this player"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formMute(id), null))
                    .build());
            buttons.add(ActionButton.builder(lang("tempmute", "Temp Mute"))
                    .tooltip(lang("tempmute-tooltip", "Mute this player for a set time"))
                    .width(200)
                    .action(DialogAction.customClick(DialogKeys.formTempmute(id), null))
                    .build());
        }

        buttons.add(ActionButton.builder(lang("back", "Back"))
                .width(200)
                .action(DialogAction.customClick(DialogKeys.PM_BACK, null))
                .build());

        String status = target.isOnline()
                ? langRaw("status-online", "&aOnline")
                : langRaw("status-offline", "&7Offline");

        String header = langRaw("player-info", "&7Player: &b%player%  &7Status: %status%")
                .replace("%player%", name)
                .replace("%status%", status);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(legacy(header)));

        if (banned) {
            body.add(DialogBody.plainMessage(lang("is-banned", "&cThis player is banned.")));
        }
        if (muted) {
            String muteInfo = langRaw("is-muted", "&eMuted: &f%reason%")
                    .replace("%reason%", plugin.getPunishmentManager().getMuteReason(id));
            body.add(DialogBody.plainMessage(legacy(muteInfo)));
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(legacy(
                                langRaw("player-title", "&bManage: &f%player%").replace("%player%", name)))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(1)
                        .build())
        );
    }

    // ==================== PUNISHMENT FORMS ====================

    /** Reason only form, used by ban, banip and mute. */
    public Dialog createReasonForm(OfflinePlayer target, String action) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        String title = langRaw("form-" + action + "-title", "Confirm action")
                .replace("%player%", name);
        String info = langRaw("form-" + action + "-info", "&7Target: &b%player%")
                .replace("%player%", name);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(legacy(title))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(legacy(info))))
                        .inputs(List.of(
                                DialogInput.text(IN_REASON, lang("reason-label", "Reason"))
                                        .width(300)
                                        .maxLength(120)
                                        .initial(langRaw("default-reason", "No reason provided"))
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(lang("confirm", "Confirm"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.apply(action, target.getUniqueId()), null))
                                .build(),
                        ActionButton.builder(lang("cancel", "Cancel"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.playerView(target.getUniqueId()), null))
                                .build()
                ))
        );
    }

    /** Confirmation form with no inputs, used by clear inventory. */
    public Dialog createConfirmForm(OfflinePlayer target, String action) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        String title = langRaw("form-" + action + "-title", "Confirm action")
                .replace("%player%", name);
        String info = langRaw("form-" + action + "-info", "&7Target: &b%player%")
                .replace("%player%", name);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(legacy(title))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(legacy(info)),
                                DialogBody.plainMessage(lang("clear-warning",
                                        "&cThis cannot be undone."))
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(lang("confirm", "Confirm"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.apply(action, target.getUniqueId()), null))
                                .build(),
                        ActionButton.builder(lang("cancel", "Cancel"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.playerView(target.getUniqueId()), null))
                                .build()
                ))
        );
    }

    /** Reason and duration form, used by tempban, tempbanip and tempmute. */
    public Dialog createTimedForm(OfflinePlayer target, String action) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        String title = langRaw("form-" + action + "-title", "Confirm action")
                .replace("%player%", name);
        String info = langRaw("form-" + action + "-info", "&7Target: &b%player%")
                .replace("%player%", name);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(legacy(title))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(legacy(info)),
                                DialogBody.plainMessage(lang("duration-hint",
                                        "&8Examples: 30m, 2h, 7d, 1w, 1d12h"))
                        ))
                        .inputs(List.of(
                                DialogInput.text(IN_REASON, lang("reason-label", "Reason"))
                                        .width(300)
                                        .maxLength(120)
                                        .initial(langRaw("default-reason", "No reason provided"))
                                        .build(),
                                DialogInput.text(IN_DURATION, lang("duration-label", "Duration"))
                                        .width(300)
                                        .maxLength(24)
                                        .initial(langRaw("default-duration", "1d"))
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(lang("confirm", "Confirm"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.apply(action, target.getUniqueId()), null))
                                .build(),
                        ActionButton.builder(lang("cancel", "Cancel"))
                                .width(140)
                                .action(DialogAction.customClick(
                                        DialogKeys.playerView(target.getUniqueId()), null))
                                .build()
                ))
        );
    }

    private ActionButton statButton(String key, String fallback, String value) {
        String template = langRaw("stat-" + key, fallback);
        return ActionButton.builder(legacy(template.replace("%value%", value)))
                .width(200)
                .build();
    }

    // ==================== STATS ====================

    private int safeStat(OfflinePlayer target, Statistic statistic) {
        try {
            return target.getStatistic(statistic);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String formatPlaytime(OfflinePlayer target) {
        int ticks;
        try {
            ticks = target.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Throwable ignored) {
            return "0h 0m";
        }
        long totalMinutes = ticks / 20L / 60L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours + "h " + minutes + "m";
    }

    private long totalMined(OfflinePlayer target) {
        long total = 0;
        for (Material material : Material.values()) {
            if (!material.isBlock() || material.isLegacy()) continue;
            try {
                total += target.getStatistic(Statistic.MINE_BLOCK, material);
            } catch (Throwable ignored) {
                // Material is not tracked by this statistic.
            }
        }
        return total;
    }

    // ==================== DIALOG KEYS ====================

    /** Keys used by the player manager. UUIDs are valid inside key paths. */
    public static final class DialogKeys {
        public static final Key PM_OPEN = Key.key(DialogManager.NAMESPACE, "pm/open");
        public static final Key PM_SEARCH = Key.key(DialogManager.NAMESPACE, "pm/search");
        public static final Key PM_BACK = Key.key(DialogManager.NAMESPACE, "pm/back");

        public static final String VIEW_PREFIX = "pm/view/";
        public static final String INV_PREFIX = "pm/inv/";
        public static final String PAGE_PREFIX = "pm/page/";

        /** Opens a punishment form, for example pm/form/ban/<uuid>. */
        public static final String FORM_PREFIX = "pm/form/";
        /** Applies a punishment, for example pm/apply/ban/<uuid>. */
        public static final String APPLY_PREFIX = "pm/apply/";

        public static Key playerView(UUID uuid) {
            return Key.key(DialogManager.NAMESPACE, VIEW_PREFIX + uuid);
        }

        public static final String ENDER_PREFIX = "pm/ender/";

        public static Key inventory(UUID uuid) {
            return Key.key(DialogManager.NAMESPACE, INV_PREFIX + uuid);
        }

        public static Key enderchest(UUID uuid) {
            return Key.key(DialogManager.NAMESPACE, ENDER_PREFIX + uuid);
        }

        public static Key formClear(UUID uuid) { return form("clear", uuid); }

        public static Key page(int page) {
            return Key.key(DialogManager.NAMESPACE, PAGE_PREFIX + page);
        }

        public static Key form(String action, UUID uuid) {
            return Key.key(DialogManager.NAMESPACE, FORM_PREFIX + action + "/" + uuid);
        }

        public static Key apply(String action, UUID uuid) {
            return Key.key(DialogManager.NAMESPACE, APPLY_PREFIX + action + "/" + uuid);
        }

        public static Key formBan(UUID uuid) { return form("ban", uuid); }
        public static Key formTempban(UUID uuid) { return form("tempban", uuid); }
        public static Key formBanip(UUID uuid) { return form("banip", uuid); }
        public static Key formTempbanip(UUID uuid) { return form("tempbanip", uuid); }
        public static Key formMute(UUID uuid) { return form("mute", uuid); }
        public static Key formTempmute(UUID uuid) { return form("tempmute", uuid); }

        public static Key unban(UUID uuid) { return apply("unban", uuid); }
        public static Key unmute(UUID uuid) { return apply("unmute", uuid); }

        private DialogKeys() {}
    }
}
