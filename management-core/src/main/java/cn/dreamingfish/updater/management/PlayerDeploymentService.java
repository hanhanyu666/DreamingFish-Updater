package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.jar.JarFile;

/** Creates a small updater overlay that can be merged into a Minecraft instance elsewhere. */
public final class PlayerDeploymentService {
    private static final String PLAYER_HOME = "DreamingFishUpdater";

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ObjectStore objects;
    private final JsonCodec json;
    private final PlayerProgramService programs;

    public PlayerDeploymentService(ManagementPaths paths, ManagementDatabase database,
                                   JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.objects = new ObjectStore(paths);
        this.json = json;
        this.programs = new PlayerProgramService(paths, database, json);
    }

    public PreparedDeployment create(String projectId, String platform,
                                     String releaseId, Path outputParent,
                                     Path bootstrapAgent) {
        ProjectRecord project = database.requireProject(projectId);
        StoredRelease release = database.findRelease(projectId, releaseId)
                .orElseThrow(() -> new ManagementException(
                        "Unknown release: " + releaseId));
        StoredPlayerProgram program = programs.latest(projectId, platform)
                .orElseThrow(() -> new ManagementException(
                        "No player program has been published for " + platform));
        Path parent = outputParent.toAbsolutePath().normalize();
        requireSafeDirectory(parent, "Deployment output directory");
        Path agent = bootstrapAgent.toAbsolutePath().normalize();
        requireAgent(agent);

        String folderName = safeName(projectId + "-player-deployment-"
                + release.displayVersion() + "-" + program.version());
        Path target = parent.resolve(folderName).normalize();
        if (!target.getParent().equals(parent)) {
            throw new ManagementException("Deployment folder escapes the selected output directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManagementException("Deployment output already exists: " + target);
        }

        Path temporary;
        try {
            temporary = Files.createTempDirectory(parent,
                    ".dfs-deployment-" + UUID.randomUUID() + "-");
        } catch (IOException e) {
            throw new ManagementException("Unable to create deployment output", e);
        }
        boolean moved = false;
        try {
            Path playerHome = Files.createDirectories(temporary.resolve(PLAYER_HOME));
            PlayerProgramManifest manifest = readProgram(program);
            materializeProgram(program, manifest, playerHome);
            writeActiveState(playerHome, program.version(), manifest.launchPath());

            Path bootstrap = Files.createDirectories(
                    temporary.resolve(".dreamingfish-bootstrap"));
            AtomicFiles.copyReplace(agent, bootstrap.resolve("bootstrap-agent.jar"));
            var bundled = new BundledReleasePreparer(paths, database, json)
                    .prepareBaseline(projectId, releaseId, temporary, playerHome);
            var preparedProgram = new PlayerProgramBootstrapper(paths, database, json)
                    .prepare(projectId, platform, playerHome);
            writeBindingAndCover(project, temporary);
            AtomicFiles.write(temporary.resolve("minecraft-jvm-argument.txt"),
                    "-javaagent:\"{verpath}.dreamingfish-bootstrap/bootstrap-agent.jar\"\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            AtomicFiles.write(temporary.resolve("README.txt"), readme(
                    project, release, program).getBytes(StandardCharsets.UTF_8));

            AtomicFiles.moveReplace(temporary, target);
            moved = true;
            return new PreparedDeployment(target, projectId, platform,
                    release.releaseId(), release.displayVersion(),
                    bundled.manifestSha256(), program.version(),
                    preparedProgram.manifestSha256());
        } catch (IOException e) {
            throw new ManagementException("Unable to create player deployment package", e);
        } finally {
            if (!moved) {
                try {
                    AtomicFiles.deleteRecursively(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary error.
                }
            }
        }
    }

    private PlayerProgramManifest readProgram(StoredPlayerProgram stored) {
        programs.read(stored.projectId(), stored.platform(), stored.version());
        try {
            return json.read(stored.manifestPath(), PlayerProgramManifest.class);
        } catch (IOException e) {
            throw new ManagementException("Unable to read signed player program", e);
        }
    }

    private void materializeProgram(StoredPlayerProgram stored,
                                    PlayerProgramManifest manifest,
                                    Path playerHome) throws IOException {
        Path programRoot = PathSafety.resolveInside(
                playerHome, "app/" + stored.version());
        Files.createDirectories(programRoot);
        for (PlayerProgramFile file : manifest.files()) {
            Path object = objects.require(file.sha256());
            objects.verify(object, file.sha256(), file.size());
            Path target = PathSafety.resolveInside(programRoot, file.path());
            AtomicFiles.copyReplace(object, target);
        }
    }

    private static void writeActiveState(Path playerHome, String version,
                                         String launcher) throws IOException {
        String programRoot = "app/" + version;
        String contents = "schema=1\n"
                + "version=" + version + "\n"
                + "launcher=" + programRoot + "/" + launcher + "\n"
                + "programRoot=" + programRoot + "\n"
                + "manifestSha256=\n"
                + "timeoutSeconds=3600\n";
        AtomicFiles.write(playerHome.resolve("state/active-player.properties"),
                contents.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBindingAndCover(ProjectRecord project, Path root) throws IOException {
        String bundledCover = project.branding().coverObject() == null
                ? null : ".dreamingfish-bootstrap/project-cover";
        byte[] binding = json.writePretty(new ProjectService(paths, database)
                .bindingFor(project, PLAYER_HOME, bundledCover));
        Path bootstrap = root.resolve(".dreamingfish-bootstrap");
        AtomicFiles.write(bootstrap.resolve("project-binding.json"), binding);
        if (bundledCover != null) {
            Path object = objects.require(project.branding().coverObject());
            objects.verify(object, project.branding().coverObject(), Files.size(object));
            AtomicFiles.copyReplace(object, root.resolve(
                    bundledCover.replace('/', java.io.File.separatorChar)));
        }
    }

    private static void requireAgent(Path agent) {
        if (!Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(agent)) {
            throw new ManagementException("Bootstrap Agent is missing from the management installation: " + agent);
        }
        try (JarFile jar = new JarFile(agent.toFile())) {
            String premain = jar.getManifest() == null ? null
                    : jar.getManifest().getMainAttributes().getValue("Premain-Class");
            if (!"cn.dreamingfish.updater.bootstrap.BootstrapAgent".equals(premain)) {
                throw new ManagementException("Bootstrap Agent file has an invalid manifest");
            }
        } catch (IOException e) {
            throw new ManagementException("Unable to verify Bootstrap Agent", e);
        }
    }

    private static void requireSafeDirectory(Path directory, String label) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new ManagementException(label + " does not exist or is unsafe: " + directory);
        }
    }

    private static String safeName(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "dreamingfish-player-deployment" : normalized;
    }

    private static String readme(ProjectRecord project, StoredRelease release,
                                 StoredPlayerProgram program) {
        return """
                DreamingFish 玩家端首次部署包

                项目：%s (%s)
                整合包基线：%s / %s
                玩家端程序：%s

                1. 把本目录中的全部内容合并到开启版本隔离的 Minecraft 实例根目录。
                2. 在 PCL 对应版本的 JVM 参数中加入 minecraft-jvm-argument.txt 里的整行参数。
                3. 正常从 PCL 启动游戏。缺失或变更的托管文件会在启动前下载。

                本包不包含 Minecraft 本体。签名基线必须与原整合包版本对应，不能随意换成其它历史版本。
                """.formatted(project.displayName(), project.id(),
                release.displayVersion(), release.releaseId(), program.version());
    }

    public record PreparedDeployment(
            Path outputDirectory,
            String projectId,
            String platform,
            String releaseId,
            String releaseDisplayVersion,
            String releaseManifestSha256,
            String playerVersion,
            String playerManifestSha256
    ) {
    }
}
