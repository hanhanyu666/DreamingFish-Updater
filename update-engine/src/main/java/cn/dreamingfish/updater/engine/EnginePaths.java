package cn.dreamingfish.updater.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

record EnginePaths(
        Path instanceRoot,
        Path playerHome,
        Path cacheObjects,
        Path state,
        Path transactions,
        Path downloads,
        Path forcedSyncBackups,
        Path installationState,
        Path trustState,
        Path installedManifest,
        Path installedSignature,
        Path bundledManifest,
        Path bundledSignature,
        Path instanceLock,
        Path gameLock
) {
    static EnginePaths of(Path instanceRoot, Path playerHome) {
        Path normalizedInstance = instanceRoot.toAbsolutePath().normalize();
        Path normalizedHome = playerHome.toAbsolutePath().normalize();
        Path state = normalizedHome.resolve("state");
        return new EnginePaths(
                normalizedInstance,
                normalizedHome,
                normalizedHome.resolve("cache/objects/sha256"),
                state,
                state.resolve("transactions"),
                normalizedHome.resolve("staging/downloads"),
                normalizedHome.resolve("backups/forced-sync"),
                state.resolve("verified-installation.json"),
                state.resolve("trust-state.json"),
                state.resolve("release-manifest.json"),
                state.resolve("release-manifest.sig"),
                normalizedInstance.resolve(".dreamingfish-bootstrap/bundled-release/manifest.json"),
                normalizedInstance.resolve(".dreamingfish-bootstrap/bundled-release/manifest.sig"),
                state.resolve("instance.lock"),
                normalizedInstance.resolve(".dreamingfish-bootstrap/game.lock")
        );
    }

    void createDirectories() throws IOException {
        createSafeDirectory(playerHome, playerHome);
        createSafeDirectory(playerHome, cacheObjects);
        createSafeDirectory(playerHome, state);
        createSafeDirectory(playerHome, transactions);
        createSafeDirectory(playerHome, downloads);
        createSafeDirectory(playerHome, forcedSyncBackups);
        createSafeDirectory(instanceRoot, gameLock.getParent());
    }

    Path cacheObject(String sha256) {
        return cacheObjects.resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    Path partialObject(String sha256) {
        return downloads.resolve(sha256 + ".part");
    }

    private static void createSafeDirectory(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IOException("Updater directory escapes its local root: " + directory);
        }
        Files.createDirectories(normalizedRoot);
        Path current = normalizedRoot;
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
            throw new IOException("Updater root is not a safe local directory: " + current);
        }
        for (Path segment : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current)) {
                throw new IOException("Updater directory traverses an unsafe entry: " + current);
            }
        }
    }
}
