package cn.dreamingfish.updater.player;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record PlayerArguments(
        boolean preview,
        Integer bootstrapPort,
        String bootstrapToken,
        Path instanceRoot,
        Path bindingFile,
        String playerName,
        String playerUuid,
        String launcherBrand,
        String launcherVersion
) {
    static PlayerArguments parse(List<String> arguments) {
        if (arguments.size() == 1 && arguments.getFirst().equals("--preview")) {
            return new PlayerArguments(true, null, null, null, null,
                    "Hanyu", "8667ba71b85a4004af54457a9734eed7", "PCL2", "2.9.4");
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < arguments.size(); i += 2) {
            if (i + 1 >= arguments.size() || !arguments.get(i).startsWith("--")) {
                throw new IllegalArgumentException("Invalid player updater command line");
            }
            if (values.put(arguments.get(i), arguments.get(i + 1)) != null) {
                throw new IllegalArgumentException("Duplicate player updater argument: " + arguments.get(i));
            }
        }
        Set<String> required = Set.of("--bootstrap-port", "--bootstrap-token", "--instance", "--binding");
        Set<String> supported = Set.of("--bootstrap-port", "--bootstrap-token", "--instance", "--binding",
                "--player-name", "--player-uuid", "--launcher-brand", "--launcher-version");
        if (!values.keySet().containsAll(required) || !supported.containsAll(values.keySet())) {
            throw new IllegalArgumentException("Player updater received an unknown command-line argument");
        }
        int port;
        try {
            port = Integer.parseInt(required(values, "--bootstrap-port"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bootstrap port is invalid", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bootstrap port is outside the valid range");
        }
        String token = required(values, "--bootstrap-token");
        if (!token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Bootstrap token is invalid");
        }
        return new PlayerArguments(false, port, token,
                Path.of(required(values, "--instance")).toAbsolutePath().normalize(),
                Path.of(required(values, "--binding")).toAbsolutePath().normalize(),
                optionalText(values, "--player-name", 64),
                optionalUuid(values, "--player-uuid"),
                optionalText(values, "--launcher-brand", 128),
                optionalText(values, "--launcher-version", 128));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing player updater argument: " + key);
        }
        return value;
    }

    private static String optionalUuid(Map<String, String> values, String key) {
        String value = optionalText(values, key, 36);
        if (value != null && !value.matches("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")) {
            throw new IllegalArgumentException("Player UUID is invalid");
        }
        return value;
    }

    private static String optionalText(Map<String, String> values, String key, int maximumLength) {
        String value = values.get(key);
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Player updater argument is invalid: " + key);
        }
        return trimmed;
    }
}
