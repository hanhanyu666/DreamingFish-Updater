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
                        throw new WebApiException(409,"player_upgrade_required","这次发布需要先批准玩家端 " + preview.minimumPlayerVersion() + " 或更新版本，请检查玩家端程序发布状态");
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
            case "config-patch" -> {
                ConfigPatchRequest request = io.readJson(exchange, ConfigPatchRequest.class);
                String target = cn.dreamingfish.updater.protocol.PathSafety.normalizeManifestPath(required(request.target, "目标配置路径"));
                var project = root.services().database().requireProject(projectId);
                if ("pack".equals(request.scope)) {
                    if (project.rules().packRoot() == null) throw new ManagementException("请先启用外层目录");
                    cn.dreamingfish.updater.protocol.ContentRoots.validatePackPath(target);
                    target = project.rules().packRoot().prefix() + target;
                } else if (!"game".equals(request.scope)) throw new ManagementException("请选择游戏实例或整合包外层目录");
                byte[] bytes = required(request.patch, "配置合并规则").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                cn.dreamingfish.updater.protocol.ConfigPatches.validate(bytes);
                if (cn.dreamingfish.updater.protocol.ConfigPatches.isPatch(target)) throw new ManagementException("目标填写实际配置文件名，不要填写规则文件后缀");
                String source = target + cn.dreamingfish.updater.protocol.ConfigPatches.SUFFIX;
                io.sendJson(exchange, 200, root.services().transfers().stage(projectId, source,
                        new java.io.ByteArrayInputStream(bytes), bytes.length, required(request.id, "导入请求身份")));
            }
            case "pack-root" -> {
                PackRootRequest request = io.readJson(exchange, PackRootRequest.class);
                io.sendJson(exchange, 200, io.mutate(() -> java.util.Map.of("packRoot",
                        new PackRootService(root.services().paths(), root.services().database()).configure(projectId,
                                required(request.relativeRoot, "外层目录相对位置"), defaultValue(request.expectedId, ""),
                                request.layout).rules().packRoot())));
            }
            case "official-player-check" -> {
                OfficialRequest request = io.readJson(exchange, OfficialRequest.class);
                io.sendJson(exchange, 200, officialCheck(Boolean.TRUE.equals(request.refresh)));
            }
            case "official-player-prepare" -> {
                OfficialRequest request = io.readJson(exchange, OfficialRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> programView(officialPrepare(projectId, request))));
            }
            case "official-player-publish" -> {
                OfficialRequest request = io.readJson(exchange, OfficialRequest.class);
                if (request.expectedRevision == null || request.expectedRevision < 0) throw new ManagementException("请刷新并提供当前更新策略修订号");
                io.sendJson(exchange, 200, io.mutate(() -> publisher().publish(projectId, required(request.platform, "程序平台"),
                        required(request.version, "程序版本"), required(request.archiveSha256, "官方程序包摘要"),
                        request.expectedRevision, ManagementCli.VERSION)));
            }
            case "official-auto-publish" -> {
                AutoPublishRequest request = io.readJson(exchange, AutoPublishRequest.class);
                if (request.enabled == null) throw new ManagementException("请选择是否自动发布");
                io.sendJson(exchange, 200, io.mutate(() -> publisher().setAuto(projectId, request.enabled)));
                if (request.enabled) io.automaticPlayerPublishing().runSoon();
            }
            case "program-approval" -> {
                ApprovalRequest request = io.readJson(exchange, ApprovalRequest.class);
                if (request.expectedRevision == null || request.expectedRevision < 0) throw new ManagementException("请刷新并提供当前更新策略修订号");
                io.sendJson(exchange, 200, io.mutate(() -> policyService().approve(projectId,
                        required(request.platform, "程序平台"), required(request.version, "程序版本"),
                        required(request.manifestSha256, "程序清单摘要"), request.expectedRevision)));
            }
            case "program-update-policy" -> {
                PolicyRequest request = io.readJson(exchange, PolicyRequest.class);
                if (request.enabled == null || request.expectedRevision == null || request.expectedRevision < 0)
                    throw new ManagementException("请刷新并提供开关和当前更新策略修订号");
                io.sendJson(exchange, 200, io.mutate(() -> policyService().setEnabled(projectId, request.enabled, request.expectedRevision)));
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
    private cn.dreamingfish.updater.protocol.OfficialPlayerCatalog officialCheck(boolean refresh) {
        try (var service = new OfficialPlayerService(root.services().paths(), root.services().database(), root.services().json())) {
            return service.check(refresh);
        }
    }
    private StoredPlayerProgram officialPrepare(String projectId, OfficialRequest request) {
        try (var service = new OfficialPlayerService(root.services().paths(), root.services().database(), root.services().json())) {
            return service.prepare(projectId, required(request.platform, "程序平台"), required(request.version, "程序版本"),
                    required(request.archiveSha256, "官方程序包摘要"), ManagementCli.VERSION);
        }
    }
    private OfficialPlayerPublisher publisher() {
        return new OfficialPlayerPublisher(root.services().paths(), root.services().database(), root.services().json());
    }
    private PlayerUpdatePolicyService policyService() {
        return new PlayerUpdatePolicyService(root.services().paths(), root.services().database(), root.services().json());
    }
    private record ConfigPatchRequest(String id, String scope, String target, String patch) {}
    private record PackRootRequest(String relativeRoot, String expectedId, java.util.List<String> layout) {}
    private record OfficialRequest(Boolean refresh, String platform, String version, String archiveSha256, Long expectedRevision) {}
    private record AutoPublishRequest(Boolean enabled) {}
    private record ApprovalRequest(String platform, String version, String manifestSha256, Long expectedRevision) {}
    private record PolicyRequest(Boolean enabled, Long expectedRevision) {}
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
