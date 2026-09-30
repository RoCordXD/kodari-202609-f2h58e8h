package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.logging.SensitiveLogFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class ConsoleLogAppender extends AbstractAppender {

    private final RoGuard plugin;
    private static ConsoleLogAppender instance;

    protected ConsoleLogAppender(RoGuard plugin) {
        super("RoGuardConsole", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.plugin = plugin;
    }

    public static void register(RoGuard plugin) {
        instance = new ConsoleLogAppender(plugin);
        instance.start();
        ((Logger) LogManager.getRootLogger()).addAppender(instance);
    }

    public static void unregister() {
        if (instance != null) {
            ((Logger) LogManager.getRootLogger()).removeAppender(instance);
            instance.stop();
            instance = null;
        }
    }

    @Override
    public void append(LogEvent event) {
        if (plugin.getSecurityBot() == null) return;
        try {
            String msg = event.getMessage().getFormattedMessage();
            if (msg == null || msg.contains("[Discord") || msg.contains("SLF4J")) return;

            if (SensitiveLogFilter.containsExposedPassword(msg)) return;

            String logger = event.getLoggerName();
            if (logger != null && (logger.contains("jda") || logger.contains("netty"))) return;
            plugin.getSecurityBot().sendConsoleMessage("[" + event.getLevel().name() + "] " + msg);
        } catch (Exception ignored) {}
    }
}
