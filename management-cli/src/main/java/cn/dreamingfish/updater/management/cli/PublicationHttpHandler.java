package cn.dreamingfish.updater.management.cli;
import cn.dreamingfish.updater.management.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;
/** PublicationHttpHandler adapter; uses the same application services as CLI. */
final class PublicationHttpHandler {
    private final AdminWebServer io;private final ManagementCli root;
    PublicationHttpHandler(AdminWebServer io,ManagementCli root){this.io=io;this.root=root;}
    boolean handle(HttpExchange exchange,String projectId,String action)throws Exception{
        switch(action){
            case "scan" -> io.sendJson(exchange, 200,
                    io.mutate(() -> io.previewView(
                            root.services().scanner().createPreview(projectId))));
            case "publish" -> {
                PublishRequest request = io.readJson(exchange, PublishRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> {
                    String previewId = required(request.previewId, "预览标识");
                    String previewDigest = required(request.previewDigest, "预览摘要");
                    PublishPreview preview = root.services().scanner().load(projectId);
                    if (preview.requiresPlayerProgramAcknowledgement()
                            && !Boolean.TRUE.equals(request.acknowledgePlayerUpgrade)) {
                        throw playerUpgradeRequired();
                    }
                    return io.publishAndRefreshService(() ->
                            root.services().publisher().publish(
                                    projectId,
                                    required(request.displayVersion, "显示版本"),
                                    defaultValue(request.minimumPlayerVersion,
                                            ScanService.POLICY_PLAYER_VERSION),
                                    defaultValue(request.changelog, ""),
                                    previewId,
                                    previewDigest));
                }));
            }
            case "removals" -> {
                RemovalDecisionsRequest request = io.readJson(
                        exchange, RemovalDecisionsRequest.class);
                io.sendJson(exchange, 200, io.mutate(() -> io.previewView(
                        root.services().scanner().decideRemovals(
                                projectId,
                                required(request.previewId, "预览标识"),
                                required(request.previewDigest, "预览摘要"),
                                request.decisions == null
                                        ? List.of() : request.decisions))));
            }
            case "rollback" -> {
                RollbackRequest request = io.readJson(exchange, RollbackRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> {
                    if (!Boolean.TRUE.equals(request.acknowledgePlayerUpgrade)
                            && !root.services().scanner().policyPlayerPublished(projectId)) {
                        throw playerUpgradeRequired();
                    }
                    return io.publishAndRefreshService(() ->
                            root.services().publisher().rollback(
                                    projectId,
                                    required(request.targetReleaseId, "目标发布"),
                                    required(request.displayVersion, "显示版本"),
                                    request.changelog));
                }));
            }
            case "programs" -> {
                PlayerProgramRequest request = io.readJson(exchange, PlayerProgramRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> programView(
                        root.services().playerPrograms().publish(
                                projectId,
                                defaultValue(request.platform, "windows-x64"),
                                defaultValue(request.version, ""),
                                Path.of(required(request.sourceDirectory, "玩家端程序目录")),
                                defaultValue(request.launchPath, ""),
                                defaultValue(request.minimumBootstrapVersion, "0.1.2")
                        ))));
            }
            default->{return false;}
        }
        return true;
    }
    private record PublishRequest(
            String displayVersion,
            String minimumPlayerVersion,
            String changelog,
            String previewId,
            String previewDigest,
            Boolean acknowledgePlayerUpgrade
    ) {
    }
    private record RemovalDecisionsRequest(List<RemovalDecision> decisions, String previewId, String previewDigest) {
    }
    private record RollbackRequest(
            String targetReleaseId,
            String displayVersion,
            String changelog,
            Boolean acknowledgePlayerUpgrade
    ) {
    }
    private record PlayerProgramRequest(
            String platform,
            String version,
            String sourceDirectory,
            String launchPath,
            String minimumBootstrapVersion
    ) {
    }
}
