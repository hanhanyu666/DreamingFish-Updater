package cn.dreamingfish.updater.management.cli;
import cn.dreamingfish.updater.management.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;
/** DistributionHttpHandler adapter; uses the same application services as CLI. */
final class DistributionHttpHandler {
    private final AdminWebServer io;private final ManagementCli root;
    DistributionHttpHandler(AdminWebServer io,ManagementCli root){this.io=io;this.root=root;}
    boolean handle(HttpExchange exchange,String projectId,String action)throws Exception{
        switch(action){
            case "deployment" -> {
                DeploymentRequest request = io.readJson(
                        exchange, DeploymentRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> {
                    var prepared = root.services().deployments().create(
                            projectId,
                            defaultValue(request.platform, "windows-x64"),
                            required(request.releaseId, "整合包发布版本"),
                            Path.of(required(request.outputDirectory, "输出父目录")),
                            root.bootstrapAgentPath());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("outputDirectory",
                            prepared.outputDirectory().toString());
                    result.put("releaseId", prepared.releaseId());
                    result.put("releaseDisplayVersion",
                            prepared.releaseDisplayVersion());
                    result.put("playerVersion", prepared.playerVersion());
                    return result;
                }));
            }
            case "distribution-export" -> {
                StaticDistributionRequest request = io.readJson(
                        exchange, StaticDistributionRequest.class);
                io.sendJson(exchange, 201, io.mutate(() -> {
                    var exported = root.services().staticDistribution().exportProject(
                            projectId,
                            Path.of(required(request.outputDirectory, "静态分发目录")));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("projectId", exported.projectId());
                    result.put("outputDirectory",
                            exported.outputDirectory().toString());
                    result.put("generatedAt", exported.generatedAt());
                    result.put("releaseCount", exported.releaseCount());
                    result.put("playerProgramCount",
                            exported.playerProgramCount());
                    result.put("objectCount", exported.objectCount());
                    result.put("copiedObjectCount",
                            exported.copiedObjectCount());
                    result.put("reusedObjectCount",
                            exported.reusedObjectCount());
                    result.put("totalObjectBytes",
                            exported.totalObjectBytes());
                    result.put("copiedObjectBytes",
                            exported.copiedObjectBytes());
                    return result;
                }));
            }
            case "distribution-webdav" -> {
                DistributionWebDavRequest request = io.readJson(
                        exchange, DistributionWebDavRequest.class);
                io.sendJson(exchange, 200, io.mutate(() -> {
                    Path output = Path.of(required(
                            request.outputDirectory, "静态分发目录"));
                    if (!Boolean.FALSE.equals(request.exportFirst)) {
                        root.services().staticDistribution()
                                .exportProject(projectId, output);
                    }
                    var result = root.services().distributionUploader().uploadWebDav(
                            output,
                            new WebDavUploadConfiguration(
                                    URI.create(required(request.baseUrl,
                                            "WebDAV / HTTP PUT 地址")),
                                    defaultValue(request.username, ""),
                                    request.password == null ? "" : request.password));
                    return distributionUploadView(result);
                }));
            }
            case "distribution-s3" -> {
                DistributionS3Request request = io.readJson(
                        exchange, DistributionS3Request.class);
                io.sendJson(exchange, 200, io.mutate(() -> {
                    Path output = Path.of(required(
                            request.outputDirectory, "静态分发目录"));
                    if (!Boolean.FALSE.equals(request.exportFirst)) {
                        root.services().staticDistribution()
                                .exportProject(projectId, output);
                    }
                    S3AddressingStyle style;
                    try {
                        style = S3AddressingStyle.valueOf(defaultValue(
                                request.addressingStyle, "PATH")
                                .toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new ManagementException("S3 寻址方式无效");
                    }
                    var result = root.services().distributionUploader().uploadS3(
                            output,
                            new S3UploadConfiguration(
                                    URI.create(required(request.endpoint,
                                            "S3 API Endpoint")),
                                    required(request.region, "S3 Region"),
                                    required(request.bucket, "S3 Bucket"),
                                    defaultValue(request.prefix, ""),
                                    required(request.accessKeyId,
                                            "Access Key ID"),
                                    request.secretAccessKey == null
                                            ? "" : request.secretAccessKey,
                                    request.sessionToken == null
                                            ? "" : request.sessionToken,
                                    style));
                    return distributionUploadView(result);
                }));
            }
            default->{return false;}
        }
        return true;
    }
    private record DeploymentRequest(
            String outputDirectory,
            String platform,
            String releaseId
    ) {
    }
    private record StaticDistributionRequest(String outputDirectory) {
    }
    private record DistributionWebDavRequest(
            String outputDirectory,
            String baseUrl,
            String username,
            String password,
            Boolean exportFirst
    ) {
    }
    private record DistributionS3Request(
            String outputDirectory,
            String endpoint,
            String region,
            String bucket,
            String prefix,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            String addressingStyle,
            Boolean exportFirst
    ) {
    }
}
