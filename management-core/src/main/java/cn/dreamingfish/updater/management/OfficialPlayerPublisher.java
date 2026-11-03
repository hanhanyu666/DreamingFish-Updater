package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Publishing an official player is the same project-signed publication as a manual upload: download,
 * verify the official signature, re-sign for the project and approve the exact version in one step.
 */
public final class OfficialPlayerPublisher {
    /** Owner opt-in for publishing each newer official version automatically; not a signed document. */
    public record AutoPublish(int schemaVersion, boolean enabled, Instant updatedAt, Instant checkedAt,
                              String publishedVersion, Instant publishedAt, String problem) {
        static AutoPublish off() { return new AutoPublish(1, false, null, null, null, null, null); }
    }

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final java.util.function.Supplier<OfficialPlayerService> officials;

    public OfficialPlayerPublisher(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this(paths, database, json, () -> new OfficialPlayerService(paths, database, json));
    }
    OfficialPlayerPublisher(ManagementPaths paths, ManagementDatabase database, JsonCodec json,
                            java.util.function.Supplier<OfficialPlayerService> officials) {
        this.paths = paths; this.database = database; this.json = json; this.officials = officials;
    }

    public PlayerUpdatePolicy publish(String projectId, String platform, String version, String archiveHash,
                                      long expectedRevision, String managementVersion) {
        try (var official = officials.get()) {
            return publish(official, projectId, platform, version, archiveHash, expectedRevision, managementVersion);
        }
    }

    private PlayerUpdatePolicy publish(OfficialPlayerService official, String projectId, String platform, String version,
                                       String archiveHash, long expectedRevision, String managementVersion) {
        var policies = new PlayerUpdatePolicyService(paths, database, json);
        if (expectedRevision != policies.read(projectId).revision())
            throw new ManagementException("更新策略已被修改，请刷新后重新确认");
        var program = official.prepare(projectId, platform, version, archiveHash, managementVersion);
        ApplicationTasks.committing("正在发布给玩家");
        return policies.approve(projectId, platform, version, program.manifestSha256(), expectedRevision);
    }

    private Path file(String projectId) {
        database.requireProject(projectId);
        return paths.playerPrograms().resolve(projectId).resolve("official-auto-publish.json");
    }

    public AutoPublish auto(String projectId) {
        Path file = file(projectId);
        try {
            PathSafety.assertSafePathTree(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return AutoPublish.off();
            if (Files.size(file) > 16 * 1024) throw new ManagementException("自动发布设置过大");
            var value = json.read(file, AutoPublish.class);
            if (value == null || value.schemaVersion() != 1) throw new ManagementException("自动发布设置版本无效");
            return value;
        } catch (IOException | ProtocolException error) {
            throw new ManagementException("无法读取自动发布设置；请保留原文件后核对", error);
        }
    }

    public AutoPublish setAuto(String projectId, boolean enabled) {
        try (ProjectLock lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".auto-publish.lock"))) {
            var old = auto(projectId);
            var next = new AutoPublish(1, enabled, Instant.now(), old.checkedAt(), old.publishedVersion(), old.publishedAt(),
                    enabled ? old.problem() : null);
            AtomicFiles.write(file(projectId), json.writePretty(next));
            return next;
        } catch (IOException error) { throw new ManagementException("无法保存自动发布设置", error); }
    }

    /**
     * Publishes every platform in the official catalogue whose newer version this project has not approved.
     * A paused project stays paused; the owner resumes it explicitly.
     */
    public AutoPublish runAutomatic(String projectId, String managementVersion) {
        if (!auto(projectId).enabled()) return auto(projectId);
        List<String> published = new ArrayList<>();
        String problem = null;
        try (var official = officials.get()) {
            var policies = new PlayerUpdatePolicyService(paths, database, json);
            for (var release : official.check(false).releases()) {
                var policy = policies.read(projectId);
                if (!policy.enabled() && !policy.targets().isEmpty()) { problem = "玩家端升级已暂停，没有自动发布新版"; break; }
                var target = policy.targets().get(release.platform());
                if (target != null && SemanticVersion.parse(release.version()).compareTo(SemanticVersion.parse(target.version())) <= 0)
                    continue;
                publish(official, projectId, release.platform(), release.version(), release.archiveSha256(), policy.revision(), managementVersion);
                published.add(release.version());
            }
        } catch (ManagementException error) {
            // Another check or publish held a lock: not a failure to show the owner, the next pass retries.
            if (ProjectLock.busy(error) && published.isEmpty()) return auto(projectId);
            problem = ProjectLock.busy(error) ? null : error.getMessage();
        }
        try (ProjectLock lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".auto-publish.lock"))) {
            var old = auto(projectId);
            var now = Instant.now();
            var next = new AutoPublish(1, old.enabled(), old.updatedAt(), now,
                    published.isEmpty() ? old.publishedVersion() : String.join("、", new LinkedHashSet<>(published)),
                    published.isEmpty() ? old.publishedAt() : now, problem);
            AtomicFiles.write(file(projectId), json.writePretty(next));
            return next;
        } catch (IOException error) { throw new ManagementException("无法记录自动发布结果", error); }
    }

    /** One pass over all projects; each project's problem is recorded instead of stopping the others. */
    public void runAutomaticForAll(String managementVersion) {
        for (var project : database.listProjects()) {
            try { if (auto(project.id()).enabled()) runAutomatic(project.id(), managementVersion); }
            catch (RuntimeException ignored) { /* Recorded per project when possible; retried on the next pass. */ }
        }
    }
}
