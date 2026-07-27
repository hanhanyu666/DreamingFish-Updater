package cn.dreamingfish.updater.engine;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

record ActivePlayerState(
        String version,
        String launcher,
        String programRoot,
        String manifestSha256,
        List<String> arguments,
        long timeoutSeconds
) {
    ActivePlayerState {
        manifestSha256 = manifestSha256 == null ? "" : manifestSha256;
        arguments = List.copyOf(arguments);
    }

    ActivePlayerState(String version, String launcher, String programRoot,
                      List<String> arguments, long timeoutSeconds) {
        this(version, launcher, programRoot, "", arguments, timeoutSeconds);
    }

    static Optional<ActivePlayerState> load(Path playerHome) {
        Path path = playerHome.resolve("state/active-player.properties");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            if (!"1".equals(properties.getProperty("schema"))) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Unsupported active player program configuration");
            }
            long timeout = Long.parseLong(properties.getProperty("timeoutSeconds", "3600"));
            return Optional.of(readState(properties, "", timeout));
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to read active player program configuration", e);
        }
    }

    static Optional<ActivePlayerState> loadForRunningVersion(Path playerHome, String runningVersion) {
        Path path = playerHome.resolve("state/active-player.properties");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            if (!"1".equals(properties.getProperty("schema"))) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Unsupported active player program configuration");
            }
            long timeout = Long.parseLong(properties.getProperty("timeoutSeconds", "3600"));
            ActivePlayerState active = readState(properties, "", timeout);
            if (active.version().equals(runningVersion)) return Optional.of(active);
            String fallbackVersion = properties.getProperty("fallbackVersion");
            if (fallbackVersion != null && fallbackVersion.equals(runningVersion)) {
                return Optional.of(readState(properties, "fallback", timeout));
            }
            return Optional.of(active);
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to read active player program configuration", e);
        }
    }

    static void activate(Path playerHome, ActivePlayerState next, ActivePlayerState previous) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schema", "1");
        properties.setProperty("version", next.version());
        properties.setProperty("launcher", next.launcher());
        properties.setProperty("programRoot", next.programRoot());
        properties.setProperty("manifestSha256", next.manifestSha256());
        properties.setProperty("timeoutSeconds", Long.toString(next.timeoutSeconds()));
        writeArguments(properties, "arg.", next.arguments());
        if (previous != null && !previous.launcher().equals(next.launcher())) {
            properties.setProperty("fallbackVersion", previous.version());
            properties.setProperty("fallbackLauncher", previous.launcher());
            properties.setProperty("fallbackProgramRoot", previous.programRoot());
            properties.setProperty("fallbackManifestSha256", previous.manifestSha256());
            writeArguments(properties, "fallbackArg.", previous.arguments());
        }
        StringWriter writer = new StringWriter();
        properties.store(writer, "DreamingFish active player program");
        AtomicFileSupport.write(playerHome.resolve("state/active-player.properties"),
                writer.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> readArguments(Properties properties, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String value = properties.getProperty(prefix + i);
            if (value == null) break;
            values.add(value);
        }
        return values;
    }

    private static ActivePlayerState readState(Properties properties, String prefix, long timeout) {
        boolean fallback = !prefix.isEmpty();
        String argumentPrefix = fallback ? "fallbackArg." : "arg.";
        String version = require(properties, fallback ? "fallbackVersion" : "version");
        String launcher = require(properties, fallback ? "fallbackLauncher" : "launcher");
        String programRoot = properties.getProperty(
                fallback ? "fallbackProgramRoot" : "programRoot", "");
        String manifestSha256 = properties.getProperty(
                fallback ? "fallbackManifestSha256" : "manifestSha256", "").trim();
        return new ActivePlayerState(version, launcher, programRoot, manifestSha256,
                readArguments(properties, argumentPrefix), timeout);
    }

    private static void writeArguments(Properties properties, String prefix, List<String> values) {
        for (int i = 0; i < values.size(); i++) properties.setProperty(prefix + i, values.get(i));
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Missing active player program setting: " + key);
        }
        return value;
    }
}
