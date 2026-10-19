package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;

/** Shared application use case for CLI and HTTP binding / instance preparation. */
public final class PlayerInstanceService {
    private final ManagementPaths paths;private final ManagementDatabase database;private final JsonCodec json;
    private final ProjectService projects;private final ObjectStore objects;
    public PlayerInstanceService(ManagementPaths paths,ManagementDatabase database,JsonCodec json){this.paths=paths;this.database=database;this.json=json;projects=new ProjectService(paths,database);objects=new ObjectStore(paths);}
    public record Prepared(Path output,ProjectBinding binding,PlayerProgramBootstrapper.PreparedPlayerProgram playerProgram,BundledReleasePreparer.PreparedBundledRelease bundledRelease){}
    public Prepared prepare(String projectId,Path instance,Path output,String playerHome,String platform,String releaseId,String bundledCover){
        if ((output == null) == (instance == null)) {
            throw new ManagementException("Specify exactly one of --instance or --output");
        }
        if (instance != null && (releaseId == null || releaseId.isBlank())) {
            throw new ManagementException("--release is required when preparing an instance");
        }
        if (output != null && releaseId != null) {
            throw new ManagementException("--release can only be used with --instance");
        }
        var project = database.requireProject(projectId);
        String effectiveBundledCover = bundledCover == null
                ? null
                : PathSafety.normalizeManifestPath(bundledCover);
        if (instance != null && effectiveBundledCover == null
                && project.branding().coverObject() != null) {
            effectiveBundledCover = ".dreamingfish-bootstrap/project-cover";
        }
        var binding = projects.bindingFor(project, playerHome, effectiveBundledCover);
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
                prepared = new PlayerProgramBootstrapper(paths, database, json)
                        .prepare(projectId, platform, resolvedHome);
                bundledRelease = new BundledReleasePreparer(
                        paths, database, json)
                        .prepare(projectId, releaseId, instanceRoot, resolvedHome);
                if (effectiveBundledCover != null) {
                    Path coverTarget = PathSafety.resolveInside(instanceRoot, effectiveBundledCover);
                    if (bundledCover == null) {
                        String coverHash = project.branding().coverObject();
                        Path coverSource = objects.require(coverHash);
                        if (!CryptoSupport.sha256(coverSource).equals(coverHash)) {
                            throw new ManagementException("Stored project cover is corrupt");
                        }
                        AtomicFiles.copyReplace(coverSource, coverTarget);
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
            AtomicFiles.write(target, json.write(binding));
            return new Prepared(target,binding,prepared,bundledRelease);
        }catch(IOException error){throw new ManagementException("Unable to write project binding",error);}
    }
}
