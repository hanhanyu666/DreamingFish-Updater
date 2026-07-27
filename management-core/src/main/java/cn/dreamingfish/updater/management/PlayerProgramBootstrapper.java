package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.SemanticVersion;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Prepares the first locally installed player program for Agent-side verification. */
public final class PlayerProgramBootstrapper {
    private static final long MAX_ACTIVE_STATE_BYTES = 64L * 1024L;

    private final ManagementPaths paths;
    private final JsonCodec json;
    private final PlayerProgramService programs;

    public PlayerProgramBootstrapper(ManagementPaths paths, ManagementDatabase database,
                                     JsonCodec json) {
        this.paths = paths;
        this.json = json;
        this.programs = new PlayerProgramService(paths, database, json);
    }

    public PreparedPlayerProgram prepare(String projectId, String platform, Path playerHome) {
        Path home = playerHome.toAbsolutePath().normalize();
        Path stateFile = home.resolve("state/active-player.properties");
        Properties state = readState(stateFile);
        String version = required(state, "version");
        SemanticVersion.parse(version);
        String programRootValue = PathSafety.normalizeManifestPath(required(state, "programRoot"));
        String launcherValue = PathSafety.normalizeManifestPath(required(state, "launcher"));

        StoredPlayerProgram stored = programs.read(projectId, platform, version);
        PlayerProgramManifest manifest;
        byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(stored.manifestPath());
            manifest = json.read(manifestBytes, PlayerProgramManifest.class);
        } catch (IOException e) {
            throw new ManagementException("Unable to read the signed player program manifest", e);
        }

        String expectedLauncher = programRootValue + "/" + manifest.launchPath();
        if (!launcherValue.equalsIgnoreCase(expectedLauncher)) {
            throw new ManagementException("The installed player launcher does not match the published program");
        }

        Path programRoot;
        try {
            programRoot = PathSafety.resolveInside(home, programRootValue);
        } catch (IOException e) {
            throw new ManagementException("Unable to resolve the installed player program directory", e);
        }
        verifyExactProgram(programRoot, manifest);

        Path manifestDirectory = home.resolve("state/player-programs").resolve(stored.manifestSha256());
        try {
            AtomicFiles.write(manifestDirectory.resolve("manifest.json"), manifestBytes);
            AtomicFiles.write(manifestDirectory.resolve("manifest.sig"),
                    (stored.signature() + "\n").getBytes(StandardCharsets.US_ASCII));
            state.setProperty("manifestSha256", stored.manifestSha256());
            StringWriter writer = new StringWriter();
            state.store(writer, "DreamingFish active player program");
            AtomicFiles.write(stateFile, writer.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ManagementException("Unable to prepare the installed player program trust state", e);
        }
        return new PreparedPlayerProgram(version, stored.manifestSha256(), programRoot,
                manifestDirectory.resolve("manifest.json"));
    }

    private Properties readState(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || Files.size(path) > MAX_ACTIVE_STATE_BYTES) {
                throw new ManagementException("Player bundle is missing a safe active-player.properties file: " + path);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            if (!"1".equals(properties.getProperty("schema"))) {
                throw new ManagementException("Unsupported active player program configuration");
            }
            return properties;
        } catch (IOException e) {
            throw new ManagementException("Unable to read the player bundle state", e);
        }
    }

    private void verifyExactProgram(Path root, PlayerProgramManifest manifest) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new ManagementException("Installed player program directory is missing or unsafe: " + root);
        }
        Map<String, PlayerProgramFile> expected = new HashMap<>();
        for (PlayerProgramFile file : manifest.files()) {
            expected.put(fold(file.path()), file);
            Path installed;
            try {
                installed = PathSafety.resolveInside(root, file.path());
                if (!Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(installed) != file.size()
                        || !CryptoSupport.sha256(installed).equals(file.sha256())) {
                    throw new ManagementException("Installed player program file differs from its signed release: "
                            + file.path());
                }
            } catch (IOException e) {
                throw new ManagementException("Unable to verify installed player program file: " + file.path(), e);
            }
        }

        Set<String> actual = new HashSet<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException("Installed player program contains a symbolic link: " + relative);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String folded = fold(PathSafety.normalizeManifestPath(relative));
                    if (!actual.add(folded) || !expected.containsKey(folded)) {
                        throw new ManagementException("Installed player program contains an unsigned file: " + relative);
                    }
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ManagementException("Installed player program contains an unsupported entry: " + relative);
                }
            }
        } catch (IOException e) {
            throw new ManagementException("Unable to enumerate the installed player program", e);
        }
        if (!actual.equals(expected.keySet())) {
            throw new ManagementException("Installed player program is incomplete");
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ManagementException("Missing active player setting: " + key);
        }
        return value.trim();
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public record PreparedPlayerProgram(
            String version,
            String manifestSha256,
            Path programRoot,
            Path manifestPath
    ) {
    }
}
