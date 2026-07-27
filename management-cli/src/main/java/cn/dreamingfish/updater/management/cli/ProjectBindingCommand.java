package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.BundledReleasePreparer;
import cn.dreamingfish.updater.management.PlayerProgramBootstrapper;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.PathSafety;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@CommandLine.Command(name = "binding", description = "Export a player project binding file")
final class ProjectBindingCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Option(names = "--output", description = "Export only the binding JSON to this file")
    Path output;
    @CommandLine.Option(names = "--instance",
            description = "Prepare a runnable player bundle in this Minecraft instance")
    Path instance;
    @CommandLine.Option(names = "--player-home", defaultValue = "DreamingFishUpdater")
    String playerHome;
    @CommandLine.Option(names = "--platform", defaultValue = "windows-x64")
    String platform;
    @CommandLine.Option(names = "--release",
            description = "Immutable release ID represented by the distributable instance")
    String releaseId;
    @CommandLine.Option(names = "--bundled-cover", description = "Cover path relative to the instance directory")
    String bundledCover;

    @Override
    public void run() {
        if ((output == null) == (instance == null)) {
            throw new ManagementException("Specify exactly one of --instance or --output");
        }
        if (instance != null && (releaseId == null || releaseId.isBlank())) {
            throw new ManagementException("--release is required when preparing an instance");
        }
        if (output != null && releaseId != null) {
            throw new ManagementException("--release can only be used with --instance");
        }
        ManagementCli root = parent.root;
        var services = root.services();
        var project = services.database().requireProject(projectId);
        String effectiveBundledCover = bundledCover == null
                ? null
                : PathSafety.normalizeManifestPath(bundledCover);
        if (instance != null && effectiveBundledCover == null
                && project.branding().coverObject() != null) {
            effectiveBundledCover = ".dreamingfish-bootstrap/project-cover";
        }
        var binding = services.projects().bindingFor(project, playerHome, effectiveBundledCover);
        try {
            Path target;
            PlayerProgramBootstrapper.PreparedPlayerProgram prepared = null;
            BundledReleasePreparer.PreparedBundledRelease bundledRelease = null;
            if (instance != null) {
                Path instanceRoot = instance.toAbsolutePath().normalize();
                if (!Files.isDirectory(instanceRoot, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(instanceRoot)) {
                    throw new ManagementException("Minecraft instance directory does not exist or is unsafe: "
                            + instanceRoot);
                }
                Path configuredHome = Path.of(playerHome);
                Path resolvedHome = configuredHome.isAbsolute()
                        ? configuredHome.toAbsolutePath().normalize()
                        : instanceRoot.resolve(configuredHome).normalize();
                if (resolvedHome.equals(instanceRoot)) {
                    throw new ManagementException("Player updater directory cannot be the instance root");
                }
                if (resolvedHome.startsWith(instanceRoot.resolve(".dreamingfish-bootstrap"))) {
                    throw new ManagementException(
                            "Player updater directory cannot be inside the bootstrap directory");
                }
                Path bootstrapDirectory = instanceRoot.resolve(".dreamingfish-bootstrap");
                Files.createDirectories(bootstrapDirectory);
                if (!Files.isDirectory(bootstrapDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(bootstrapDirectory)) {
                    throw new ManagementException("Minecraft bootstrap directory is unsafe: "
                            + bootstrapDirectory);
                }
                Path agent = bootstrapDirectory.resolve("bootstrap-agent.jar");
                if (!Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(agent)) {
                    throw new ManagementException("Minecraft instance is missing bootstrap-agent.jar; "
                            + "extract the player bundle before creating its binding");
                }
                prepared = new PlayerProgramBootstrapper(services.paths(), services.database(), services.json())
                        .prepare(projectId, platform, resolvedHome);
                bundledRelease = new BundledReleasePreparer(
                        services.paths(), services.database(), services.json())
                        .prepare(projectId, releaseId, instanceRoot, resolvedHome);
                if (effectiveBundledCover != null) {
                    Path coverTarget = PathSafety.resolveInside(instanceRoot, effectiveBundledCover);
                    if (bundledCover == null) {
                        String coverHash = project.branding().coverObject();
                        Path coverSource = services.objects().require(coverHash);
                        if (!CryptoSupport.sha256(coverSource).equals(coverHash)) {
                            throw new ManagementException("Stored project cover is corrupt");
                        }
                        copyAtomic(coverSource, coverTarget);
                        if (!CryptoSupport.sha256(coverTarget).equals(coverHash)) {
                            throw new ManagementException("Bundled project cover failed verification");
                        }
                    } else if (!Files.isRegularFile(coverTarget, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(coverTarget)) {
                        throw new ManagementException("Bundled cover does not exist safely inside the instance: "
                                + coverTarget);
                    }
                }
                target = bootstrapDirectory.resolve("project-binding.json");
            } else {
                target = output.toAbsolutePath().normalize();
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            writeAtomic(target, services.json().write(binding));
            if (root.jsonOutput) {
                var result = new java.util.LinkedHashMap<String, Object>();
                result.put("output", target.toString());
                result.put("binding", binding);
                if (prepared != null) result.put("playerProgram", prepared);
                if (bundledRelease != null) result.put("bundledRelease", bundledRelease);
                root.printJson(result);
            } else if (prepared != null) {
                root.out().println("Prepared signed player program " + prepared.version()
                        + ", bundled release " + bundledRelease.displayVersion()
                        + " (" + bundledRelease.releaseId() + "), and wrote project binding to " + target);
            } else {
                root.out().println("Exported binding JSON to " + target
                        + "; use --instance to prepare a runnable player bundle");
            }
        } catch (IOException e) {
            throw new ManagementException("Unable to write project binding", e);
        }
    }

    private static void copyAtomic(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomic(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            moveAtomic(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
