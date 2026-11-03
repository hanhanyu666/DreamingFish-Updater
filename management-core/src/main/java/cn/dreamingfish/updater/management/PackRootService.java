package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;

/** Configuration changes participate in the ordinary project preview digest. */
public final class PackRootService {
    private final ManagementPaths paths; private final ManagementDatabase database;
    public PackRootService(ManagementPaths paths, ManagementDatabase database) { this.paths = paths; this.database = database; }
    public ProjectRecord configure(String projectId, String relativeRoot, String expectedId) {
        return configure(projectId, relativeRoot, expectedId, null);
    }

    /** {@code layout} lists the folder names from the root down to the instance; null uses the version-isolated default. */
    public ProjectRecord configure(String projectId, String relativeRoot, String expectedId, java.util.List<String> layout) {
        database.requireProject(projectId);
        try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            var project = database.requireProject(projectId); var old = project.rules().packRoot();
            if (!Objects.equals(old == null ? "" : old.id(), expectedId)) throw new ManagementException("外层目录配置已变化，请刷新后重试");
            var position = new PackRootSpec(old == null ? UUID.randomUUID().toString() : old.id(), relativeRoot);
            var next = layout == null || layout.isEmpty() ? position.withDefaultLayout()
                    : new PackRootSpec(position.id(), position.relativeRoot(), layout);
            if (old != null && !old.sameLocation(next)) throw new ManagementException("已授权的目录位置不能直接改写；请先迁移玩家部署和历史恢复记录");
            if (old != null && old.layout() != null && !old.layout().equals(next.layout()))
                throw new ManagementException("已有玩家按原目录结构核对过，不能直接改写");
            ProjectRules rules = project.rules().withPackRoot(next);
            database.updateProject(projectId, project.displayName(), project.sourceDirectory(), project.publicBaseUrl(), project.branding(), rules);
            Path source = PathSafety.resolveInside(project.sourceDirectory(), next.prefix().replaceAll("/+$", ""));
            PathSafety.createSafeDirectories(source);
            return database.requireProject(projectId);
        } catch (IOException error) { throw new ManagementException("无法保存外层更新目录", error); }
    }
}
