package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Uploads a completed static export without persisting remote credentials. */
public final class StaticDistributionUploader {
    private static final int PARALLEL_UPLOADS = 4;
    private static final int MAX_FILES = 1_000_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JsonCodec json;
    private final HttpClient client;

    public StaticDistributionUploader(JsonCodec json) {
        this(json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    StaticDistributionUploader(JsonCodec json, HttpClient client) {
        this.json = Objects.requireNonNull(json, "json");
        this.client = Objects.requireNonNull(client, "client");
    }

    public DistributionUploadResult uploadWebDav(
            Path exportDirectory, WebDavUploadConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Path root = validateExport(exportDirectory);
        URI base = normalizedBaseUri(configuration.baseUri(),
                !configuration.username().isBlank() || !configuration.password().isBlank(),
                "WebDAV");
        String authorization = basicAuthorization(configuration);
        List<DistributionFile> files = listFiles(root);
        ensureWebDavCollection(base, authorization);
        createWebDavDirectories(base, files, authorization);
        Map<String, RemoteIndexEntry> remoteIndex = readWebDavIndex(
                base, authorization);

        UploadStats stats = uploadOrdered(files, file -> {
            RemoteIndexEntry indexed = remoteIndex.get(file.relativePath());
            if (indexed != null && indexed.sha256().equals(file.sha256())
                    && indexed.size() == file.size()
                    && webDavObjectExists(base, file, authorization)) {
                return false;
            }
            putWebDav(base, file, authorization);
            return true;
        });

        Map<String, RemoteIndexEntry> completed = new LinkedHashMap<>();
        for (DistributionFile file : files) {
            completed.put(file.relativePath(),
                    new RemoteIndexEntry(file.sha256(), file.size()));
        }
        byte[] indexPayload = json.writePretty(new WebDavUploadIndex(
                1, Instant.now(), completed));
        putWebDavBytes(resolve(base, ".dreamingfish-upload-index.json"),
                indexPayload, "application/json; charset=utf-8",
                "no-cache, max-age=0", authorization,
                CryptoSupport.sha256(indexPayload));
        return result("WebDAV / HTTP PUT", base.toString(), files, stats);
    }

    public DistributionUploadResult uploadS3(
            Path exportDirectory, S3UploadConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        validateS3(configuration);
        Path root = validateExport(exportDirectory);
        List<DistributionFile> files = listFiles(root);
        UploadStats stats = uploadOrdered(files, file -> {
            URI destination = s3ObjectUri(configuration, file.relativePath());
            if (!file.mutable() && s3ObjectMatches(
                    configuration, destination, file)) {
                return false;
            }
            putS3(configuration, destination, file);
            return true;
        });
        String destination = s3ObjectUri(configuration, "").toString();
        return result("S3 compatible", destination, files, stats);
    }

    private DistributionUploadResult result(String provider, String destination,
                                            List<DistributionFile> files,
                                            UploadStats stats) {
        long totalBytes = files.stream().mapToLong(DistributionFile::size).sum();
        return new DistributionUploadResult(
                provider, destination, Instant.now(), files.size(),
                stats.uploadedCount(), stats.skippedCount(), totalBytes,
                stats.uploadedBytes());
    }

    private UploadStats uploadOrdered(List<DistributionFile> files,
                                      UploadOperation operation) {
        List<DistributionFile> immutable = files.stream()
                .filter(file -> !file.mutable()).toList();
        List<DistributionFile> mutable = files.stream()
                .filter(DistributionFile::mutable).toList();
        List<UploadOutcome> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(
                PARALLEL_UPLOADS,
                Thread.ofPlatform().daemon().name("dfs-distribution-upload-", 0).factory())) {
            List<Future<UploadOutcome>> futures = immutable.stream()
                    .map(file -> executor.submit(uploadTask(file, operation)))
                    .toList();
            for (Future<UploadOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException e) {
                    futures.forEach(item -> item.cancel(true));
                    Thread.currentThread().interrupt();
                    throw new ManagementException("外部托管上传已中断", e);
                } catch (ExecutionException e) {
                    futures.forEach(item -> item.cancel(true));
                    Throwable cause = e.getCause();
                    if (cause instanceof ManagementException management) {
                        throw management;
                    }
                    throw new ManagementException("上传静态分发文件失败", cause);
                }
            }
        }
        for (DistributionFile file : mutable) {
            outcomes.add(callUpload(file, operation));
        }
        int uploaded = (int) outcomes.stream().filter(UploadOutcome::uploaded).count();
        long bytes = outcomes.stream().filter(UploadOutcome::uploaded)
                .mapToLong(UploadOutcome::size).sum();
        return new UploadStats(uploaded, outcomes.size() - uploaded, bytes);
    }

    private Callable<UploadOutcome> uploadTask(DistributionFile file,
                                               UploadOperation operation) {
        return () -> callUpload(file, operation);
    }

    private UploadOutcome callUpload(DistributionFile file,
                                     UploadOperation operation) {
        try {
            return new UploadOutcome(operation.upload(file), file.size());
        } catch (IOException e) {
            throw new ManagementException(
                    "上传文件失败：" + file.relativePath(), e);
        }
    }

    private Path validateExport(Path selected) {
        if (selected == null) throw new ManagementException("请选择静态分发目录");
        Path root = selected.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new ManagementException("静态分发目录不存在或不是安全的普通目录");
        }
        Path marker = root.resolve(".dreamingfish-static-export.json");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)) {
            throw new ManagementException("所选目录不是 DreamingFish 静态分发目录");
        }
        try {
            StaticDistributionService.ExportMarker value = json.read(
                    marker, StaticDistributionService.ExportMarker.class);
            if (!"COMPLETE".equals(value.status())) {
                throw new ManagementException("上一次静态导出没有完整结束，请先重新导出");
            }
        } catch (IOException e) {
            throw new ManagementException("无法读取静态分发目录标记", e);
        }
        return root;
    }

    private List<DistributionFile> listFiles(Path root) {
        try (var stream = Files.walk(root)) {
            List<Path> entries = stream.toList();
            if (entries.size() > MAX_FILES) {
                throw new ManagementException("静态分发目录文件数量超过安全限制");
            }
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new ManagementException(
                            "静态分发目录包含符号链接，已停止上传：" + entry);
                }
            }
            List<DistributionFile> files = new ArrayList<>();
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(entry).toString().replace('\\', '/');
                long size = Files.size(entry);
                files.add(new DistributionFile(entry, relative,
                        CryptoSupport.sha256(entry), size,
                        mutableRoute(relative), contentType(relative),
                        cacheControl(relative)));
            }
            files.sort(Comparator.comparing(DistributionFile::relativePath));
            return List.copyOf(files);
        } catch (IOException e) {
            throw new ManagementException("无法读取静态分发目录", e);
        }
    }

    private void createWebDavDirectories(URI base,
                                         List<DistributionFile> files,
                                         String authorization) {
        java.util.TreeSet<String> directories = new java.util.TreeSet<>(
                Comparator.comparingInt(String::length).thenComparing(value -> value));
        for (DistributionFile file : files) {
            int slash = file.relativePath().lastIndexOf('/');
            while (slash > 0) {
                directories.add(file.relativePath().substring(0, slash));
                slash = file.relativePath().lastIndexOf('/', slash - 1);
            }
        }
        for (String directory : directories) {
            URI uri = resolve(base, directory + "/");
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .method("MKCOL", HttpRequest.BodyPublishers.noBody());
            addAuthorization(builder, authorization);
            RemoteResponse response = send(builder.build());
            if (response.statusCode() == 200 || response.statusCode() == 201
                    || response.statusCode() == 204
                    || response.statusCode() == 405 || response.statusCode() == 501) {
                continue;
            }
            throw remoteFailure("创建 WebDAV 目录", uri, response);
        }
    }

    private void ensureWebDavCollection(URI uri, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody());
        addAuthorization(builder, authorization);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() == 200 || response.statusCode() == 201
                || response.statusCode() == 204
                || response.statusCode() == 405 || response.statusCode() == 501) {
            return;
        }
        throw remoteFailure("确认 WebDAV 根目录", uri, response);
    }

    private Map<String, RemoteIndexEntry> readWebDavIndex(
            URI base, String authorization) {
        URI uri = resolve(base, ".dreamingfish-upload-index.json");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30)).GET()
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity");
        addAuthorization(builder, authorization);
        RemoteResponse response = send(builder.build(), MAX_INDEX_BYTES);
        if (response.statusCode() == 404) return Map.of();
        if (response.statusCode() != 200) {
            throw remoteFailure("读取 WebDAV 上传索引", uri, response);
        }
        try {
            WebDavUploadIndex index = json.read(response.body(), WebDavUploadIndex.class);
            return index.schemaVersion() == 1 && index.files() != null
                    ? Map.copyOf(index.files()) : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private boolean webDavObjectExists(URI base, DistributionFile file,
                                       String authorization) {
        URI uri = resolve(base, file.relativePath());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        addAuthorization(builder, authorization);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() != 200 && response.statusCode() != 204) return false;
        return response.headers().firstValueAsLong("Content-Length")
                .stream().anyMatch(size -> size == file.size());
    }

    private void putWebDav(URI base, DistributionFile file,
                           String authorization) throws IOException {
        URI uri = resolve(base, file.relativePath());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", file.contentType())
                .header("Cache-Control", file.cacheControl())
                .header("X-DFS-SHA256", file.sha256())
                .PUT(HttpRequest.BodyPublishers.ofFile(file.path()));
        addAuthorization(builder, authorization);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() != 200 && response.statusCode() != 201
                && response.statusCode() != 204) {
            throw remoteFailure("上传 WebDAV 文件", uri, response);
        }
    }

    private void putWebDavBytes(URI uri, byte[] bytes, String contentType,
                                String cacheControl, String authorization,
                                String sha256) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", contentType)
                .header("Cache-Control", cacheControl)
                .header("X-DFS-SHA256", sha256)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        addAuthorization(builder, authorization);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() != 200 && response.statusCode() != 201
                && response.statusCode() != 204) {
            throw remoteFailure("写入 WebDAV 上传索引", uri, response);
        }
    }

    private boolean s3ObjectMatches(S3UploadConfiguration configuration,
                                    URI uri, DistributionFile file) {
        Map<String, String> signedHeaders = new LinkedHashMap<>();
        signedHeaders.put("x-amz-content-sha256", EMPTY_SHA256);
        addSessionToken(configuration, signedHeaders);
        AwsV4Signer.SignedRequest signed = AwsV4Signer.sign(
                "HEAD", uri, configuration.region(),
                configuration.accessKeyId(), configuration.secretAccessKey(),
                EMPTY_SHA256, signedHeaders, Instant.now());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        addSignedHeaders(builder, signedHeaders, signed);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw remoteFailure("检查 S3 对象", uri, response);
        }
        return response.headers().firstValue("x-amz-meta-dfs-sha256")
                .map(file.sha256()::equals).orElse(false)
                && response.headers().firstValueAsLong("Content-Length")
                .stream().anyMatch(size -> size == file.size());
    }

    private void putS3(S3UploadConfiguration configuration, URI uri,
                       DistributionFile file) throws IOException {
        Map<String, String> signedHeaders = new LinkedHashMap<>();
        signedHeaders.put("x-amz-content-sha256", file.sha256());
        signedHeaders.put("x-amz-meta-dfs-sha256", file.sha256());
        addSessionToken(configuration, signedHeaders);
        AwsV4Signer.SignedRequest signed = AwsV4Signer.sign(
                "PUT", uri, configuration.region(),
                configuration.accessKeyId(), configuration.secretAccessKey(),
                file.sha256(), signedHeaders, Instant.now());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", file.contentType())
                .header("Cache-Control", file.cacheControl())
                .PUT(HttpRequest.BodyPublishers.ofFile(file.path()));
        addSignedHeaders(builder, signedHeaders, signed);
        RemoteResponse response = send(builder.build());
        if (response.statusCode() != 200 && response.statusCode() != 201
                && response.statusCode() != 204) {
            throw remoteFailure("上传 S3 对象", uri, response);
        }
    }

    private static void addSessionToken(S3UploadConfiguration configuration,
                                        Map<String, String> headers) {
        if (!configuration.sessionToken().isBlank()) {
            headers.put("x-amz-security-token", configuration.sessionToken());
        }
    }

    private static void addSignedHeaders(HttpRequest.Builder builder,
                                         Map<String, String> headers,
                                         AwsV4Signer.SignedRequest signed) {
        headers.forEach(builder::header);
        builder.header("x-amz-date", signed.timestamp());
        builder.header("Authorization", signed.authorization());
    }

    private RemoteResponse send(HttpRequest request) {
        return send(request, MAX_RESPONSE_BYTES);
    }

    private RemoteResponse send(HttpRequest request, int maximumResponseBytes) {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body;
                try (InputStream input = response.body()) {
                    body = input.readNBytes(maximumResponseBytes + 1);
                }
                if (body.length > maximumResponseBytes) {
                    throw new ManagementException(
                            "外部托管响应超过安全限制：" + request.uri());
                }
                RemoteResponse result = new RemoteResponse(
                        response.statusCode(), response.headers(), body);
                if (attempt < 2 && transientStatus(result.statusCode())) {
                    waitBeforeRetry(attempt);
                    continue;
                }
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ManagementException("外部托管请求已中断", e);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < 2) {
                    waitBeforeRetry(attempt);
                    continue;
                }
            }
        }
        throw new ManagementException(
                "无法连接外部托管服务：" + request.uri(), lastFailure);
    }

    private static boolean transientStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementException("外部托管请求已中断", e);
        }
    }

    private static ManagementException remoteFailure(
            String action, URI uri, RemoteResponse response) {
        String detail = new String(response.body(), StandardCharsets.UTF_8)
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
        return new ManagementException(action + "失败（HTTP "
                + response.statusCode() + "）：" + uri
                + (detail.isEmpty() ? "" : "；" + detail));
    }

    private static String basicAuthorization(WebDavUploadConfiguration configuration) {
        if (configuration.username().isBlank() && configuration.password().isBlank()) {
            return "";
        }
        String value = configuration.username() + ":" + configuration.password();
        return "Basic " + Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static void addAuthorization(HttpRequest.Builder builder,
                                         String authorization) {
        if (!authorization.isBlank()) builder.header("Authorization", authorization);
    }

    private static URI normalizedBaseUri(URI value, boolean hasCredentials,
                                         String label) {
        validateEndpoint(value, hasCredentials, label);
        String ascii = value.toASCIIString();
        return URI.create(ascii.endsWith("/") ? ascii : ascii + "/");
    }

    private static void validateEndpoint(URI uri, boolean hasCredentials,
                                         String label) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (!uri.getScheme().equalsIgnoreCase("http")
                && !uri.getScheme().equalsIgnoreCase("https"))) {
            throw new ManagementException(label + " 地址必须是完整的 HTTP(S) 地址，且不能包含账户、查询或片段");
        }
        if (hasCredentials && !uri.getScheme().equalsIgnoreCase("https")
                && !loopback(uri.getHost())) {
            throw new ManagementException(label + " 使用账户或密钥时必须使用 HTTPS");
        }
    }

    private static boolean loopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static URI resolve(URI base, String relative) {
        return URI.create(base.toASCIIString() + encodePath(relative));
    }

    private static void validateS3(S3UploadConfiguration configuration) {
        validateEndpoint(configuration.endpoint(), true, "S3 API");
        if (configuration.region() == null
                || !configuration.region().matches("[A-Za-z0-9-]{1,64}")) {
            throw new ManagementException("S3 Region 无效");
        }
        if (configuration.bucket() == null
                || !configuration.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || configuration.bucket().contains("..")) {
            throw new ManagementException("S3 Bucket 名称无效");
        }
        if (configuration.accessKeyId() == null
                || configuration.accessKeyId().isBlank()
                || configuration.secretAccessKey() == null
                || configuration.secretAccessKey().isBlank()) {
            throw new ManagementException("S3 Access Key ID 和 Secret Access Key 不能为空");
        }
        normalizePrefix(configuration.prefix());
        if (configuration.addressingStyle() == S3AddressingStyle.VIRTUAL_HOST
                && configuration.endpoint().getHost().matches("[0-9.:]+")) {
            throw new ManagementException("IP 地址不能使用 S3 虚拟主机寻址，请改用路径寻址");
        }
    }

    private static URI s3ObjectUri(S3UploadConfiguration configuration,
                                   String relative) {
        URI endpoint = configuration.endpoint();
        String host = endpoint.getHost();
        StringBuilder path = new StringBuilder();
        String basePath = endpoint.getRawPath();
        if (basePath != null && !basePath.isBlank() && !basePath.equals("/")) {
            path.append(basePath.startsWith("/") ? basePath : "/" + basePath);
        }
        if (configuration.addressingStyle() == S3AddressingStyle.VIRTUAL_HOST) {
            host = configuration.bucket() + "." + host;
        } else {
            path.append('/').append(encodeSegment(configuration.bucket()));
        }
        String prefix = normalizePrefix(configuration.prefix());
        if (!prefix.isEmpty()) path.append('/').append(encodePath(prefix));
        if (relative != null && !relative.isEmpty()) {
            path.append('/').append(encodePath(relative));
        } else if (path.isEmpty() || path.charAt(path.length() - 1) != '/') {
            path.append('/');
        }
        String authorityHost = host.contains(":") ? "[" + host + "]" : host;
        int port = endpoint.getPort();
        String authority = authorityHost + (port < 0 ? "" : ":" + port);
        return URI.create(endpoint.getScheme() + "://" + authority + path);
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) return "";
        for (String segment : normalized.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new ManagementException("S3 对象前缀包含无效路径段");
            }
        }
        return normalized;
    }

    private static String encodePath(String value) {
        String[] segments = value.replace('\\', '/').split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) encoded.append('/');
            encoded.append(encodeSegment(segments[index]));
        }
        return encoded.toString();
    }

    private static String encodeSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_' || unsigned == '.'
                    || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append("0123456789ABCDEF".charAt(unsigned >>> 4));
                result.append("0123456789ABCDEF".charAt(unsigned & 0x0f));
            }
        }
        return result.toString();
    }

    private static boolean mutableRoute(String path) {
        if (path.equals("healthz") || path.equals("_headers")
                || path.endsWith(".txt")
                || path.equals(".dreamingfish-static-export.json")) return true;
        if (path.contains("/releases/") || path.contains("/versions/")
                || path.startsWith("v1/objects/sha256/")) return false;
        return path.endsWith("/latest") || path.endsWith("/latest.sig")
                || path.endsWith("/presentation")
                || path.endsWith("/presentation.sig")
                || path.endsWith("/history");
    }

    private static String cacheControl(String path) {
        return mutableRoute(path) ? "no-cache, max-age=0"
                : "public, max-age=31536000, immutable";
    }

    private static String contentType(String path) {
        if (path.startsWith("v1/objects/sha256/")) {
            return "application/octet-stream";
        }
        if (path.endsWith(".sig") || path.endsWith(".txt")
                || path.equals("_headers")) {
            return "text/plain; charset=utf-8";
        }
        return "application/json; charset=utf-8";
    }

    private record DistributionFile(
            Path path,
            String relativePath,
            String sha256,
            long size,
            boolean mutable,
            String contentType,
            String cacheControl
    ) {
    }

    private record RemoteIndexEntry(String sha256, long size) {
    }

    private record WebDavUploadIndex(
            int schemaVersion,
            Instant generatedAt,
            Map<String, RemoteIndexEntry> files
    ) {
    }

    private record UploadOutcome(boolean uploaded, long size) {
    }

    private record UploadStats(
            int uploadedCount,
            int skippedCount,
            long uploadedBytes
    ) {
    }

    private record RemoteResponse(
            int statusCode,
            HttpHeaders headers,
            byte[] body
    ) {
    }

    @FunctionalInterface
    private interface UploadOperation {
        boolean upload(DistributionFile file) throws IOException;
    }
}
