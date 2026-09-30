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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds all RoGuard dialogs.
 * Every label is read from language.yml so it can be translated.
 * Only usable when the Paper Dialog API (1.21.7+) is present.
 */
public final class DialogManager {

    public static final String NAMESPACE = "roguard";

    // Custom click keys
    public static final Key KEY_LOGIN = Key.key(NAMESPACE, "auth/login");
    public static final Key KEY_REGISTER = Key.key(NAMESPACE, "auth/register");
    public static final Key KEY_SETTINGS_SAVE = Key.key(NAMESPACE, "settings/save");

    // Input ids - Minecraft only accepts plain lowercase alphanumeric input keys.
    public static final String IN_PASSWORD = "password";
    public static final String IN_PASSWORD_CONFIRM = "passwordconfirm";

    /** Settings input id -> feature path inside protect.yml. */
    public static final java.util.LinkedHashMap<String, String> FEATURE_INPUTS = new java.util.LinkedHashMap<>();
    static {
        FEATURE_INPUTS.put("commandverification", "command-verification");
        FEATURE_INPUTS.put("staff2fa", "staff-2fa");
        FEATURE_INPUTS.put("commandlog", "command-log");
        FEATURE_INPUTS.put("consolemirror", "console-mirror");
        FEATURE_INPUTS.put("autodeop", "auto-deop");
        FEATURE_INPUTS.put("antivpn", "anti-vpn");
    }

    // Additional settings input ids
    public static final String IN_ANTIBOT = "antibot";
    public static final String IN_AUTH = "auth";
    public static final String IN_PLAYER2FA = "player2fa";

    /** Map feature input id back to the feature's display-name key. */
    public static String featureNameKey(String inputId) {
        return FEATURE_INPUTS.getOrDefault(inputId, inputId);
    }

    private static Boolean supported;

    private final RoGuard plugin;

    public DialogManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    /** True when the server provides the Paper Dialog API (1.21.7+). */
    public static boolean isSupported() {
        if (supported == null) {
            try {
                Class.forName("io.papermc.paper.dialog.Dialog");
                Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
                supported = true;
            } catch (Throwable ignored) {
                supported = false;
            }
        }
        return supported;
    }

    /** Reads a dialog label from language.yml, falling back to plain text. */
    private Component dlg(String key, String fallback) {
        String raw = plugin.getLanguageManager().get("dialog." + key);
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) {
            return Component.text(fallback);
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    private Component colored(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    // ==================== LOGIN ====================

    public Dialog createLoginDialog(String playerName, String errorMessage) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(dlg("login-welcome", "Welcome back, " + playerName)
                .replaceText(builder -> builder.match("%player%").replacement(playerName))));
        if (errorMessage != null && !errorMessage.isEmpty()) {
            body.add(DialogBody.plainMessage(colored(errorMessage)));
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dlg("login-title", "Login"))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text(IN_PASSWORD, dlg("login-password-label", "Password"))
                                        .width(300)
                                        .maxLength(64)
                                        .build()
                        ))
                        .build())
                .type(DialogType.notice(
                        ActionButton.builder(dlg("login-button", "Login"))
                                .tooltip(dlg("login-button-tooltip", "Click to log in"))
                                .width(150)
                                .action(DialogAction.customClick(KEY_LOGIN, null))
                                .build()
                ))
        );
    }

    // ==================== REGISTER ====================

    public Dialog createRegisterDialog(String playerName, String errorMessage) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(dlg("register-welcome", "Create an account for " + playerName)
                .replaceText(builder -> builder.match("%player%").replacement(playerName))));
        body.add(DialogBody.plainMessage(dlg("register-hint", "Minimum 6 characters.")
                .replaceText(builder -> builder.match("%min%")
                        .replacement(String.valueOf(plugin.getConfigManager().getMinPasswordLength())))));
        if (errorMessage != null && !errorMessage.isEmpty()) {
            body.add(DialogBody.plainMessage(colored(errorMessage)));
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dlg("register-title", "Register"))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text(IN_PASSWORD, dlg("register-password-label", "Password"))
                                        .width(300).maxLength(64).build(),
                                DialogInput.text(IN_PASSWORD_CONFIRM, dlg("register-confirm-label", "Confirm"))
                                        .width(300).maxLength(64).build()
                        ))
                        .build())
                .type(DialogType.notice(
                        ActionButton.builder(dlg("register-button", "Register"))
                                .tooltip(dlg("register-button-tooltip", "Click to create your account"))
                                .width(150)
                                .action(DialogAction.customClick(KEY_REGISTER, null))
                                .build()
                ))
        );
    }

    // ==================== SETTINGS ====================

    public Dialog createSettingsDialog() {
        List<DialogInput> inputs = new ArrayList<>();

        for (java.util.Map.Entry<String, String> entry : FEATURE_INPUTS.entrySet()) {
            String inputId = entry.getKey();
            String feature = entry.getValue();
            inputs.add(DialogInput.bool(inputId, featureLabel(feature))
                    .initial(plugin.getConfigManager().isFeatureEnabled(feature)).build());
        }

        inputs.add(DialogInput.bool(IN_ANTIBOT, featureLabel("anti-bot"))
                .initial(plugin.getConfigManager().isAntiBotEnabled()).build());
        inputs.add(DialogInput.bool(IN_AUTH, featureLabel("auth"))
                .initial(plugin.getConfigManager().isAuthEnabled()).build());
        inputs.add(DialogInput.bool(IN_PLAYER2FA, featureLabel("player-2fa"))
                .initial(plugin.getConfigManager().isPlayer2FAEnabled()).build());

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dlg("settings-title", "RoGuard Settings"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(
                                dlg("settings-description", "Toggle features, then press Save."))))
                        .inputs(inputs)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(dlg("settings-save", "Save"))
                                .tooltip(dlg("settings-save-tooltip", "Apply and write to the config files"))
                                .width(140)
                                .action(DialogAction.customClick(KEY_SETTINGS_SAVE, null))
                                .build(),
                        ActionButton.builder(dlg("settings-cancel", "Cancel"))
                                .tooltip(dlg("settings-cancel-tooltip", "Discard changes"))
                                .width(140)
                                .build()
                ))
        );
    }

    /** Feature display name from language.yml. */
    private Component featureLabel(String feature) {
        String key = "feature-" + feature;
        String fallback = feature.replace('-', ' ');
        String raw = plugin.getLanguageManager().get("dialog." + key);
        if (raw == null || raw.isBlank() || raw.contains("Missing:")) {
            return Component.text(fallback);
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    /** Feature input ids exposed for the settings save handler. */
    public static java.util.LinkedHashMap<String, String> featureInputs() {
        return FEATURE_INPUTS;
    }

    private static final TextColor ACCENT = TextColor.color(0x55D7FF);
}
