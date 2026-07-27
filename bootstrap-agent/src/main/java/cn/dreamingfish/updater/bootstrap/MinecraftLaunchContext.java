package cn.dreamingfish.updater.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MinecraftLaunchContext {
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_LAUNCHER_FIELD_LENGTH = 128;

    private final String playerName;
    private final String playerUuid;
    private final String launcherBrand;
    private final String launcherVersion;

    MinecraftLaunchContext(String playerName, String playerUuid,
                           String launcherBrand, String launcherVersion) {
        this.playerName = safeText(playerName, MAX_PLAYER_NAME_LENGTH);
        this.playerUuid = safeUuid(playerUuid);
        this.launcherBrand = safeText(launcherBrand, MAX_LAUNCHER_FIELD_LENGTH);
        this.launcherVersion = safeText(launcherVersion, MAX_LAUNCHER_FIELD_LENGTH);
    }

    static MinecraftLaunchContext capture() {
        List<String> command = tokenize(System.getProperty("sun.java.command", ""));
        return new MinecraftLaunchContext(
                optionValue(command, "--username"),
                optionValue(command, "--uuid"),
                System.getProperty("minecraft.launcher.brand"),
                System.getProperty("minecraft.launcher.version"));
    }

    static MinecraftLaunchContext empty() {
        return new MinecraftLaunchContext(null, null, null, null);
    }

    void appendTo(List<String> command) {
        append(command, "--player-name", playerName);
        append(command, "--player-uuid", playerUuid);
        append(command, "--launcher-brand", launcherBrand);
        append(command, "--launcher-version", launcherVersion);
    }

    String playerName() {
        return playerName;
    }

    String playerUuid() {
        return playerUuid;
    }

    String launcherBrand() {
        return launcherBrand;
    }

    String launcherVersion() {
        return launcherVersion;
    }

    static List<String> tokenize(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) return Collections.emptyList();
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char value = commandLine.charAt(index);
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else if (value == '\\' && index + 1 < commandLine.length()
                        && commandLine.charAt(index + 1) == quote) {
                    current.append(commandLine.charAt(++index));
                } else {
                    current.append(value);
                }
                tokenStarted = true;
            } else if (value == '\'' || value == '"') {
                quote = value;
                tokenStarted = true;
            } else if (Character.isWhitespace(value)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(value);
                tokenStarted = true;
            }
        }
        if (tokenStarted) tokens.add(current.toString());
        return tokens;
    }

    private static String optionValue(List<String> arguments, String option) {
        for (int index = 0; index < arguments.size(); index++) {
            String value = arguments.get(index);
            if (option.equals(value) && index + 1 < arguments.size()) return arguments.get(index + 1);
            String prefix = option + "=";
            if (value.startsWith(prefix)) return value.substring(prefix.length());
        }
        return null;
    }

    private static void append(List<String> command, String option, String value) {
        if (value == null) return;
        command.add(option);
        command.add(value);
    }

    private static String safeUuid(String value) {
        String safe = safeText(value, 36);
        return safe != null && safe.matches("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
                ? safe : null;
    }

    private static String safeText(String value, int maximumLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) return null;
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) return null;
        }
        return trimmed;
    }
}
