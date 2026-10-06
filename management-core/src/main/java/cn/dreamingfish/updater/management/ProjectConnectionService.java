package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;

/** Reports connectivity from the management server, without claiming player reachability. */
public final class ProjectConnectionService {
    private final JsonCodec json;
    public ProjectConnectionService(JsonCodec json) { this.json = json; }

    public Result check(String projectId, String address) {
        address = address == null ? "" : address.trim();
        URI base;
        try { base = URI.create(address.trim()); }
        catch (RuntimeException error) { throw new ManagementException("请填写有效的 HTTP / HTTPS 更新地址"); }
        if (!java.util.Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new ManagementException("更新地址须为 HTTP / HTTPS 根地址，不能包含账户、查询参数或片段");
        if (!projectId.matches("[a-zA-Z0-9._-]+")) throw new ManagementException("项目 ID 无效");
        URI endpoint = URI.create(address.replaceAll("/+$", "") + "/v1/projects/" + projectId + "/latest");
        long start = System.nanoTime();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build(), info -> new LimitedBody());
                if (response.statusCode() == 404) return new Result(false, 404, elapsed(start),
                        "服务已响应，但当前项目没有最新发布，或地址路径不匹配", endpoint.toString());
                if (response.statusCode() != 200) return new Result(false, response.statusCode(), elapsed(start),
                        "更新接口返回 HTTP " + response.statusCode(), endpoint.toString());
                Map<?, ?> data = json.read(response.body(), Map.class);
                boolean belongs = projectId.equals(data.get("projectId"));
                return new Result(belongs, 200, elapsed(start), belongs
                        ? "管理服务器可以读取当前项目的更新接口" : "服务已响应，但返回的内容不属于当前项目", endpoint.toString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, elapsed(start), "检测已中断", endpoint.toString());
        } catch (Exception error) {
            return new Result(false, 0, elapsed(start), "连接或响应检查失败，请核对地址、端口与服务状态", endpoint.toString());
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return future; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 4 * 1024 * 1024) {
                    subscription.cancel(); future.completeExceptionally(new ManagementException("更新接口响应过大")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
        }
        public void onError(Throwable error) { future.completeExceptionally(error); }
        public void onComplete() { future.complete(bytes.toByteArray()); }
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    public record Result(boolean ok, int status, long milliseconds, String message, String endpoint) {}
}
