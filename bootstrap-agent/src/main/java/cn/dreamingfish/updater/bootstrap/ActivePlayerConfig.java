package cn.dreamingfish.updater.bootstrap;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class ActivePlayerConfig {
    private static final int MAX_ARGUMENTS = 32;
    private final String version;
    private final Path playerHome;
    private final Path launcher;
    private final Path programRoot;
    private final String manifestSha256;
    private final List<String> arguments;
    private final long timeoutMillis;
    private final ActivePlayerConfig fallback;

    private ActivePlayerConfig(String version, Path playerHome, Path launcher, Path programRoot,
                               String manifestSha256, List<String> arguments,
                               long timeoutMillis, ActivePlayerConfig fallback) {
        this.version = version;
        this.playerHome = playerHome;
        this.launcher = launcher;
        this.programRoot = programRoot;
        this.manifestSha256 = manifestSha256;
        this.arguments = arguments;
        this.timeoutMillis = timeoutMillis;
        this.fallback = fallback;
    }

    static ActivePlayerConfig load(Path playerHome) throws BootstrapException {
        Path configPath = playerHome.resolve("state/active-player.properties");
        try {
            if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configPath) || Files.size(configPath) > 64L * 1024L) {
                throw new BootstrapException("Active player updater configuration is missing: " + configPath);
            }
            Properties properties = new Properties();
            BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8);
            try {
                properties.load(reader);
            } finally {
                reader.close();
            }
            if (!"1".equals(properties.getProperty("schema"))) {
                throw new BootstrapException("Unsupported active player updater configuration");
            }
            long seconds = Long.parseLong(properties.getProperty("timeoutSeconds", "3600"));
            if (seconds < 30 || seconds > 7200) {
                throw new BootstrapException("Player updater timeout must be between 30 and 7200 seconds");
            }
            ActivePlayerConfig fallback = null;
            String fallbackLauncher = properties.getProperty("fallbackLauncher");
            if (fallbackLauncher != null && !fallbackLauncher.trim().isEmpty()) {
                fallback = create(playerHome,
                        properties.getProperty("fallbackVersion", "unknown"),
                        fallbackLauncher, properties.getProperty("fallbackProgramRoot", ""),
                        properties.getProperty("fallbackManifestSha256", ""),
                        readArguments(properties, "fallbackArg."),
                        seconds * 1000L, null);
            }
            return create(playerHome, required(properties, "version"), required(properties, "launcher"),
                    properties.getProperty("programRoot", ""),
                    properties.getProperty("manifestSha256", ""),
                    readArguments(properties, "arg."), seconds * 1000L, fallback);
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to read active player updater configuration", e);
        }
    }

    List<String> command(int port, String token, Path instanceRoot, Path bindingFile) {
        return command(port, token, instanceRoot, bindingFile, MinecraftLaunchContext.empty());
    }

    List<String> command(int port, String token, Path instanceRoot, Path bindingFile,
                         MinecraftLaunchContext launchContext) {
        List<String> command = new ArrayList<String>();
        command.add(launcher.toString());
        command.addAll(arguments);
        command.add("--bootstrap-port");
        command.add(Integer.toString(port));
        command.add("--bootstrap-token");
        command.add(token);
        command.add("--instance");
        command.add(instanceRoot.toAbsolutePath().normalize().toString());
        command.add("--binding");
        command.add(bindingFile.toAbsolutePath().normalize().toString());
        launchContext.appendTo(command);
        return command;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    ActivePlayerConfig fallback() {
        return fallback;
    }

    String identity() {
        return version + "\n" + launcher + "\n" + manifestSha256 + "\n" + arguments;
    }

    String version() {
        return version;
    }

    Path playerHome() {
        return playerHome;
    }

    Path launcher() {
        return launcher;
    }

    Path programRoot() {
        return programRoot;
    }

    String manifestSha256() {
        return manifestSha256;
    }

    private static ActivePlayerConfig create(Path playerHome, String version, String launcherValue,
                                             String programRootValue, String manifestSha256,
                                             List<String> arguments, long timeoutMillis,
                                             ActivePlayerConfig fallback) throws BootstrapException {
        Path launcherPath = Paths.get(launcherValue);
        Path normalizedHome = playerHome.toAbsolutePath().normalize();
        if (launcherPath.isAbsolute()) {
            throw new BootstrapException("Player updater launcher must be relative to its home directory");
        }
        Path resolved = normalizedHome.resolve(launcherPath).normalize();
        if (!resolved.startsWith(normalizedHome)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(resolved)) {
            throw new BootstrapException("Player updater launcher does not exist safely inside its home: " + resolved);
        }
        Path root = null;
        if (programRootValue != null && !programRootValue.trim().isEmpty()) {
            Path configuredRoot = Paths.get(programRootValue);
            if (configuredRoot.isAbsolute()) {
                throw new BootstrapException("Player program root must be relative to its home directory");
            }
            root = normalizedHome.resolve(configuredRoot).normalize();
            if (!root.startsWith(normalizedHome)) {
                throw new BootstrapException("Player program root escapes its home directory");
            }
        }
        return new ActivePlayerConfig(version, normalizedHome, resolved, root,
                manifestSha256 == null ? "" : manifestSha256.trim(),
                arguments, timeoutMillis, fallback);
    }

    private static List<String> readArguments(Properties properties, String prefix)
            throws BootstrapException {
        List<String> arguments = new ArrayList<String>();
        for (int i = 0; i < MAX_ARGUMENTS; i++) {
            String value = properties.getProperty(prefix + i);
            if (value == null) break;
            arguments.add(value);
        }
        if (properties.getProperty(prefix + MAX_ARGUMENTS) != null) {
            throw new BootstrapException("Player updater configuration has too many arguments");
        }
        return arguments;
    }

    private static String required(Properties properties, String key) throws BootstrapException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new BootstrapException("Missing player updater setting: " + key);
        }
        return value;
    }
}
