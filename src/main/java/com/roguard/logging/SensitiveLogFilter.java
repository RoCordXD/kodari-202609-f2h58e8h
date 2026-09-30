package com.roguard.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.AbstractFilterable;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dual-layer filter:
 * 1. Log4j2 filter drops password leaks and SLF4J warnings from loggers/appenders.
 * 2. System.out/System.err wrapper drops password leaks and SLF4J warnings from raw streams.
 */
public final class SensitiveLogFilter extends AbstractFilter {

    private static final Pattern PASSWORD_COMMAND = Pattern.compile(
            "(?i)(?:^|[\\s:])/(?:[a-z0-9_.-]+:)?"
                    + "(?:login|l|register|reg|changepassword|changepass|cp)"
                    + "(?=\\s)\\s+(\\S+)"
    );

    private static final Set<LoggerConfig> LOGGER_CONFIGS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<AbstractFilterable> APPENDERS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static SensitiveLogFilter instance;
    private static Field spigotLogCommandsField;
    private static boolean previousSpigotLogCommands;

    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static boolean streamFilterInstalled = false;

    private SensitiveLogFilter() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    public static synchronized void install() {
        if (instance != null) return;

        // Layer 1: Wrap System.out and System.err (catches SLF4J and raw console writes)
        installStreamFilter();

        // Layer 2: Disable Spigot's raw command logger
        disableBuiltInCommandLogging();

        // Layer 3: Log4j2 filter on all loggers and appenders
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            instance = new SensitiveLogFilter();
            instance.start();

            addLoggerFilter(configuration.getRootLogger());
            for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
                addLoggerFilter(loggerConfig);
            }
            for (Appender appender : configuration.getAppenders().values()) {
                if (appender instanceof AbstractFilterable filterableAppender
                        && APPENDERS.add(filterableAppender)) {
                    filterableAppender.addFilter(instance);
                }
            }

            context.updateLoggers();
        } catch (Throwable ignored) {}
    }

    private static void installStreamFilter() {
        if (streamFilterInstalled) return;
        try {
            originalOut = System.out;
            originalErr = System.err;
            System.setOut(new FilteredPrintStream(originalOut));
            System.setErr(new FilteredPrintStream(originalErr));
            streamFilterInstalled = true;
        } catch (Throwable ignored) {}
    }

    private static void uninstallStreamFilter() {
        if (!streamFilterInstalled) return;
        try {
            if (originalOut != null) System.setOut(originalOut);
            if (originalErr != null) System.setErr(originalErr);
            streamFilterInstalled = false;
        } catch (Throwable ignored) {}
    }

    private static void disableBuiltInCommandLogging() {
        try {
            Class<?> spigotConfig = Class.forName("org.spigotmc.SpigotConfig");
            Field field = spigotConfig.getDeclaredField("logCommands");
            if (!Modifier.isStatic(field.getModifiers())) return;

            field.setAccessible(true);
            previousSpigotLogCommands = field.getBoolean(null);
            field.setBoolean(null, false);
            spigotLogCommandsField = field;
        } catch (ReflectiveOperationException ignored) {}
    }

    private static void addLoggerFilter(LoggerConfig loggerConfig) {
        if (LOGGER_CONFIGS.add(loggerConfig)) loggerConfig.addFilter(instance);
    }

    public static synchronized void uninstall() {
        if (instance != null) {
            try {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                for (LoggerConfig loggerConfig : LOGGER_CONFIGS) {
                    loggerConfig.removeFilter(instance);
                }
                for (AbstractFilterable appender : APPENDERS) {
                    appender.removeFilter(instance);
                }
                LOGGER_CONFIGS.clear();
                APPENDERS.clear();
                instance.stop();
                instance = null;
                context.updateLoggers();
            } catch (Throwable ignored) {}

            if (spigotLogCommandsField != null) {
                try {
                    spigotLogCommandsField.setBoolean(null, previousSpigotLogCommands);
                } catch (IllegalAccessException ignored) {}
                spigotLogCommandsField = null;
            }
        }

        uninstallStreamFilter();
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        String formatted = event.getMessage().getFormattedMessage();
        return shouldDropMessage(formatted) ? Result.DENY : Result.NEUTRAL;
    }

    /**
     * Checks if a message should be completely dropped:
     * - Exposed password in command
     * - SLF4J internal warning noise
     */
    public static boolean shouldDropMessage(String message) {
        if (message == null) return false;
        if (message.contains("SLF4J") || message.contains("noProviders")) return true;
        return containsExposedPassword(message);
    }

    public static boolean containsExposedPassword(String message) {
        if (message == null) return false;
        Matcher matcher = PASSWORD_COMMAND.matcher(message);
        while (matcher.find()) {
            if (!matcher.group(1).matches("\\*+")) return true;
        }
        return false;
    }

    public static boolean containsPasswordCommand(String message) {
        return containsExposedPassword(message);
    }

    /**
     * PrintStream wrapper that drops sensitive lines and SLF4J warnings.
     */
    private static class FilteredPrintStream extends PrintStream {

        private final StringBuilder buffer = new StringBuilder();

        public FilteredPrintStream(OutputStream out) {
            super(out, true);
        }

        @Override
        public void print(String s) {
            if (s == null) return;
            synchronized (buffer) {
                buffer.append(s);
                if (s.contains("\n")) {
                    flushBuffer();
                }
            }
        }

        @Override
        public void println(String x) {
            print((x == null ? "null" : x) + "\n");
        }

        @Override
        public void println() {
            print("\n");
        }

        @Override
        public void print(Object obj) { print(String.valueOf(obj)); }

        @Override
        public void println(Object x) { println(String.valueOf(x)); }

        @Override
        public void print(boolean b) { print(String.valueOf(b)); }

        @Override
        public void println(boolean x) { println(String.valueOf(x)); }

        @Override
        public void print(char c) { print(String.valueOf(c)); }

        @Override
        public void println(char x) { println(String.valueOf(x)); }

        @Override
        public void print(int i) { print(String.valueOf(i)); }

        @Override
        public void println(int x) { println(String.valueOf(x)); }

        @Override
        public void print(long l) { print(String.valueOf(l)); }

        @Override
        public void println(long x) { println(String.valueOf(x)); }

        @Override
        public void print(float f) { print(String.valueOf(f)); }

        @Override
        public void println(float x) { println(String.valueOf(x)); }

        @Override
        public void print(double d) { print(String.valueOf(d)); }

        @Override
        public void println(double x) { println(String.valueOf(x)); }

        @Override
        public void print(char[] s) { print(new String(s)); }

        @Override
        public void println(char[] x) { println(new String(x)); }

        private void flushBuffer() {
            String content = buffer.toString();
            buffer.setLength(0);

            if (!shouldDropMessage(content)) {
                super.print(content);
            }
        }

        @Override
        public void flush() {
            synchronized (buffer) {
                if (buffer.length() > 0 && !shouldDropMessage(buffer.toString())) {
                    super.print(buffer.toString());
                    buffer.setLength(0);
                } else if (buffer.length() > 0) {
                    buffer.setLength(0);
                }
            }
            super.flush();
        }
    }
}
