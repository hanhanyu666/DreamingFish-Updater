package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.Hex;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PlayerPresentation;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseHistoryEntry;
import cn.dreamingfish.updater.protocol.SemanticVersion;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class PublicFileServer implements AutoCloseable {
    private static final String SERVER_NAME = "DreamingFishUpdateSystem/0.1";
    private static final int BUFFER_SIZE = 128 * 1024;
    public record Limits(int controlWorkers,int controlQueue,int downloads,int stalledSeconds,long totalSeconds) {
        public Limits(int controlWorkers,int controlQueue,int downloads,int stalledSeconds){this(controlWorkers,controlQueue,downloads,stalledSeconds,3600);}
        public Limits {
            if(controlWorkers<2||controlWorkers>32||controlQueue<1||controlQueue>1024||downloads<1||downloads>128||stalledSeconds<1||stalledSeconds>3600||totalSeconds<60||totalSeconds>86400)
                throw new IllegalArgumentException("Invalid public service limits");
        }
        public static Limits defaults(){return new Limits(Integer.getInteger("dfs.public.controlWorkers",8),Integer.getInteger("dfs.public.controlQueue",64),Integer.getInteger("dfs.public.downloads",16),Integer.getInteger("dfs.public.stalledSeconds",30),Long.getLong("dfs.public.totalSeconds",3600));}
    }
    private final Limits limits;
    private final ThreadPoolExecutor downloads;
    private final ScheduledExecutorService watchdog;
    private final ConcurrentHashMap<HttpExchange,Transfer> active=new ConcurrentHashMap<>();
    private final AtomicLong rejected=new AtomicLong(),stalled=new AtomicLong();
    private final java.util.LinkedHashMap<String,SignedPresentation> presentations=new java.util.LinkedHashMap<>();
    private static final class Transfer { volatile long progress=System.nanoTime(); final long started=progress; }
    public record Metrics(int activeDownloads,int queuedControl,long rejected,long stalled,int downloadThreads,int downloadLimit) {}
    public Metrics metrics(){return new Metrics(active.size(),((ThreadPoolExecutor)executor).getQueue().size(),rejected.get(),stalled.get(),downloads.getPoolSize(),limits.downloads());}

    private final ManagementDatabase database;
    private final ObjectStore objects;
    private final PlayerProgramService playerPrograms;
    private final ProjectKeyStore projectKeys;
    private final JsonCodec json;
    private final HttpServer server;
    private final ExecutorService executor;

    public PublicFileServer(ManagementDatabase database, ObjectStore objects,
                            InetSocketAddress address) {
        this(database,objects,address,Limits.defaults());
    }
    public PublicFileServer(ManagementDatabase database,ObjectStore objects,InetSocketAddress address,Limits limits) {
        this.limits=limits;
        HttpServerResources.configure();
        this.database = database;
        this.objects = objects;
        this.json = new JsonCodec();
        this.playerPrograms = new PlayerProgramService(objects.paths(), database, json);
        this.projectKeys = new ProjectKeyStore(objects.paths());
        try {
            server = HttpServer.create(address, 128);
        } catch (IOException e) {
            throw new ManagementException("无法监听下载服务地址 " + address
                    + "，该端口可能已经被其他进程占用", e);
        }
        executor = new ThreadPoolExecutor(limits.controlWorkers(),limits.controlWorkers(),0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.controlQueue()),Thread.ofPlatform().daemon().name("dfs-public-control-",0).factory(),new ThreadPoolExecutor.CallerRunsPolicy());
        downloads = new ThreadPoolExecutor(limits.downloads(),limits.downloads(),0,TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("dfs-public-download-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        watchdog=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("dfs-public-watchdog").factory());
        watchdog.scheduleWithFixedDelay(this::expireStalled,200,200,TimeUnit.MILLISECONDS);
        server.setExecutor(executor);
        server.createContext("/healthz", this::handleHealth);
        server.createContext("/v1/projects/", exchange->dispatch(exchange,this::handleProject));
        server.createContext("/v1/objects/sha256/", exchange->dispatch(exchange,this::handleObject));
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        active.keySet().forEach(HttpExchange::close);
        server.stop(0);watchdog.shutdownNow();downloads.shutdownNow();executor.shutdownNow();
        try {downloads.awaitTermination(5,TimeUnit.SECONDS);executor.awaitTermination(5,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
    }

    private void dispatch(HttpExchange exchange,HttpHandler handler)throws IOException {
        if(exchange.getRequestHeaders().getFirst("Transfer-Encoding")!=null ||
                !"0".equals(java.util.Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("Content-Length"),"0"))) {
            exchange.getResponseHeaders().set("Connection","close");sendError(exchange,400,"request_body_not_supported");exchange.close();return;
        }
        try {
            downloads.execute(()->{
                active.put(exchange,new Transfer());
                try {handler.handle(exchange);}catch(IOException ignored){exchange.close();}
                finally {active.remove(exchange);exchange.close();}
            });
        } catch(RejectedExecutionException full) {
            rejected.incrementAndGet();exchange.getResponseHeaders().set("Retry-After","2");
            exchange.getResponseHeaders().set("Connection","close");sendError(exchange,503,"download_capacity");exchange.close();
        }
    }
    private void expireStalled(){
        long now=System.nanoTime();
        active.forEach((exchange,transfer)->{
            if(now-transfer.progress>TimeUnit.SECONDS.toNanos(limits.stalledSeconds()) || now-transfer.started>TimeUnit.SECONDS.toNanos(limits.totalSeconds())) {
                if(active.remove(exchange,transfer)){stalled.incrementAndGet();exchange.close();}
            }
        });
    }
    private void progressed(HttpExchange exchange){var transfer=active.get(exchange);if(transfer!=null)transfer.progress=System.nanoTime();}

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!allowReadMethod(exchange)) return;
            byte[] body = json.write(java.util.Map.of("status","ok","resources",metrics()));
            commonHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            sendBytes(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private void handleProject(HttpExchange exchange) throws IOException {
        try {
            if (!allowReadMethod(exchange)) return;
            if (exchange.getRequestURI().getRawQuery() != null) {
                sendError(exchange, 400, "query_not_supported");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String prefix = "/v1/projects/";
            String remainder = path.substring(prefix.length());
            String[] segments = remainder.split("/", -1);
            if (segments.length == 2 && validProjectId(segments[0])
                    && segments[1].equals("presentation")) {
                sendPlayerPresentation(exchange, segments[0]);
                return;
            }
            if (segments.length == 2 && validProjectId(segments[0])
                    && segments[1].equals("presentation.sig")) {
                Optional<ProjectRecord> project = database.findProject(segments[0]);
                if (project.isEmpty()) {
                    sendError(exchange, 404, "project_not_found");
                    return;
                }
                sendSignature(exchange, signPlayerPresentation(project.get()), false);
                return;
            }
            if (segments.length == 2 && validProjectId(segments[0]) && segments[1].equals("latest")) {
                Optional<StoredRelease> latest = database.latestRelease(segments[0]);
                if (latest.isEmpty()) {
                    sendError(exchange, 404, "release_not_found");
                    return;
                }
                sendManifest(exchange, latest.get(), false);
                return;
            }
            if (segments.length == 2 && validProjectId(segments[0])
                    && segments[1].equals("latest.sig")) {
                Optional<StoredRelease> latest = database.latestRelease(segments[0]);
                if (latest.isEmpty()) {
                    sendError(exchange, 404, "release_not_found");
                    return;
                }
                sendSignature(exchange, latest.get().signature(), false);
                return;
            }
            if (segments.length == 2 && validProjectId(segments[0]) && segments[1].equals("history")) {
                sendReleaseHistory(exchange, segments[0]);
                return;
            }
            if (segments.length == 4 && validProjectId(segments[0])
                    && segments[1].equals("releases") && validReleaseId(segments[2])
                    && segments[3].equals("manifest")) {
                Optional<StoredRelease> release = database.findRelease(segments[0], segments[2]);
                if (release.isEmpty()) {
                    sendError(exchange, 404, "release_not_found");
                    return;
                }
                sendManifest(exchange, release.get(), true);
                return;
            }
            if (segments.length == 4 && validProjectId(segments[0])
                    && segments[1].equals("releases") && validReleaseId(segments[2])
                    && segments[3].equals("manifest.sig")) {
                Optional<StoredRelease> release = database.findRelease(segments[0], segments[2]);
                if (release.isEmpty()) {
                    sendError(exchange, 404, "release_not_found");
                    return;
                }
                sendSignature(exchange, release.get().signature(), true);
                return;
            }
            if (segments.length == 4 && validProjectId(segments[0])
                    && segments[1].equals("player") && validProjectId(segments[2])
                    && segments[3].equals("latest")) {
                Optional<StoredPlayerProgram> latest = playerPrograms.latest(segments[0], segments[2]);
                if (latest.isEmpty()) {
                    sendError(exchange, 404, "player_program_not_found");
                    return;
                }
                sendPlayerProgramManifest(exchange, latest.get(), false);
                return;
            }
            if (segments.length == 4 && validProjectId(segments[0])
                    && segments[1].equals("player") && validProjectId(segments[2])
                    && segments[3].equals("latest.sig")) {
                Optional<StoredPlayerProgram> latest = playerPrograms.latest(segments[0], segments[2]);
                if (latest.isEmpty()) {
                    sendError(exchange, 404, "player_program_not_found");
                    return;
                }
                sendSignature(exchange, latest.get().signature(), false);
                return;
            }
            if (segments.length == 6 && validProjectId(segments[0])
                    && segments[1].equals("player") && validProjectId(segments[2])
                    && segments[3].equals("versions") && validSemanticVersion(segments[4])
                    && segments[5].equals("manifest")) {
                sendPlayerProgramManifest(exchange,
                        playerPrograms.read(segments[0], segments[2], segments[4]), true);
                return;
            }
            if (segments.length == 6 && validProjectId(segments[0])
                    && segments[1].equals("player") && validProjectId(segments[2])
                    && segments[3].equals("versions") && validSemanticVersion(segments[4])
                    && segments[5].equals("manifest.sig")) {
                sendSignature(exchange,
                        playerPrograms.read(segments[0], segments[2], segments[4]).signature(), true);
                return;
            }
            sendError(exchange, 404, "not_found");
        } catch (ManagementException e) {
            sendError(exchange, 500, "storage_error");
        } finally {
            exchange.close();
        }
    }

    private void sendReleaseHistory(HttpExchange exchange, String projectId) throws IOException {
        if (database.findProject(projectId).isEmpty()) {
            sendError(exchange, 404, "project_not_found");
            return;
        }
        ReleaseHistory history = new ReleaseHistory(
                ProtocolConstants.RELEASE_HISTORY_SCHEMA_VERSION,
                projectId,
                database.listReleases(projectId).stream()
                        .map(release -> new ReleaseHistoryEntry(
                                release.releaseId(),
                                release.sequence(),
                                release.displayVersion(),
                                release.createdAt(),
                                release.changelog()))
                        .toList());
        byte[] body = json.write(history);
        String hash = CryptoSupport.sha256(body);
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("ETag", quoted(hash));
        headers.set("Cache-Control", "no-cache, max-age=0");
        if (etagMatches(exchange, hash)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBytes(exchange, 200, body);
    }

    private void sendPlayerPresentation(HttpExchange exchange, String projectId) throws IOException {
        Optional<ProjectRecord> storedProject = database.findProject(projectId);
        if (storedProject.isEmpty()) {
            sendError(exchange, 404, "project_not_found");
            return;
        }
        ProjectRecord project = storedProject.get();
        SignedPresentation signed = signPlayerPresentation(project);
        byte[] body = signed.payload();
        String hash = CryptoSupport.sha256(body);
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("ETag", quoted(hash));
        headers.set("Cache-Control", "no-cache, max-age=0");
        if (etagMatches(exchange, hash)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        headers.set(ProtocolConstants.SIGNATURE_HEADER, signed.signature());
        sendBytes(exchange, 200, body);
    }

    private synchronized SignedPresentation signPlayerPresentation(ProjectRecord project) {
        PlayerPresentation presentation = new PlayerPresentation(
                ProtocolConstants.PLAYER_PRESENTATION_SCHEMA_VERSION,
                project.id(),
                project.branding());
        ManifestValidator.validatePlayerPresentation(presentation);
        byte[] payload = json.writePretty(presentation);
        SignedPresentation cached=presentations.get(project.id());
        if(cached!=null&&java.util.Arrays.equals(cached.payload(),payload))return cached;
        String signature = Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, projectKeys.load(project)));
        SignedPresentation signed=new SignedPresentation(payload,signature);
        if(payload.length<=512*1024){presentations.put(project.id(),signed);while(presentations.size()>64)presentations.remove(presentations.keySet().iterator().next());}
        return signed;
    }

    private void sendSignature(HttpExchange exchange, SignedPresentation presentation,
                               boolean immutable) throws IOException {
        sendSignature(exchange, presentation.signature(), immutable);
    }

    private void sendSignature(HttpExchange exchange, String signature,
                               boolean immutable) throws IOException {
        byte[] body = (signature + "\n").getBytes(StandardCharsets.US_ASCII);
        String hash = CryptoSupport.sha256(body);
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "text/plain; charset=us-ascii");
        headers.set("ETag", quoted(hash));
        headers.set("Cache-Control", immutable
                ? "public, max-age=31536000, immutable"
                : "no-cache, max-age=0");
        if (etagMatches(exchange, hash)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBytes(exchange, 200, body);
    }

    private void sendPlayerProgramManifest(HttpExchange exchange, StoredPlayerProgram program,
                                           boolean immutable) throws IOException {
        byte[] body = Files.readAllBytes(program.manifestPath());
        if (!CryptoSupport.sha256(body).equals(program.manifestSha256())) {
            sendError(exchange, 500, "player_manifest_corrupt");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set(ProtocolConstants.SIGNATURE_HEADER, program.signature());
        headers.set("ETag", quoted(program.manifestSha256()));
        headers.set("Cache-Control", immutable
                ? "public, max-age=31536000, immutable"
                : "no-cache, max-age=0");
        if (etagMatches(exchange, program.manifestSha256())) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBytes(exchange, 200, body);
    }

    private void sendManifest(HttpExchange exchange, StoredRelease release, boolean immutable) throws IOException {
        byte[] body = Files.readAllBytes(release.manifestPath());
        if (!CryptoSupport.sha256(body).equals(release.manifestSha256())) {
            sendError(exchange, 500, "manifest_corrupt");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set(ProtocolConstants.SIGNATURE_HEADER, release.signature());
        headers.set("ETag", quoted(release.manifestSha256()));
        headers.set("Cache-Control", immutable
                ? "public, max-age=31536000, immutable"
                : "no-cache, max-age=0");

        if (etagMatches(exchange, release.manifestSha256())) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendBytes(exchange, 200, body);
    }

    private void handleObject(HttpExchange exchange) throws IOException {
        try {
            if (!allowReadMethod(exchange)) return;
            if (exchange.getRequestURI().getRawQuery() != null) {
                sendError(exchange, 400, "query_not_supported");
                return;
            }
            String prefix = "/v1/objects/sha256/";
            String hash = exchange.getRequestURI().getRawPath().substring(prefix.length());
            if (!Hex.isSha256(hash)) {
                sendError(exchange, 404, "object_not_found");
                return;
            }
            Path object;
            try {
                object = objects.require(hash);
            } catch (ManagementException e) {
                sendError(exchange, 404, "object_not_found");
                return;
            }
            sendObject(exchange, object, hash);
        } finally {
            exchange.close();
        }
    }

    private void sendObject(HttpExchange exchange, Path object, String hash) throws IOException {
        long size = Files.size(object);
        Headers headers = exchange.getResponseHeaders();
        commonHeaders(headers);
        headers.set("Content-Type", "application/octet-stream");
        headers.set("Accept-Ranges", "bytes");
        headers.set("ETag", quoted(hash));
        headers.set("Cache-Control", "public, max-age=31536000, immutable");

        if (etagMatches(exchange, hash)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }

        ByteRange range;
        try {
            range = parseRange(exchange.getRequestHeaders().getFirst("Range"), size);
        } catch (IllegalArgumentException e) {
            headers.set("Content-Range", "bytes */" + size);
            sendError(exchange, 416, "range_not_satisfiable");
            return;
        }
        long start = range == null ? 0 : range.start();
        long end = range == null ? size - 1 : range.end();
        long length = size == 0 ? 0 : end - start + 1;
        int status = range == null ? 200 : 206;
        if (range != null) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        headers.set("Content-Length", Long.toString(length));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, length);
        try (FileChannel input = FileChannel.open(object, StandardOpenOption.READ);
             OutputStream output = exchange.getResponseBody()) {
            input.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = input.read(buffer);
                if (read < 0) {
                    throw new IOException("Content object ended before the advertised size");
                }
                buffer.flip();
                output.write(buffer.array(), 0, read);
                progressed(exchange);
                remaining -= read;
            }
        }
    }

    private boolean allowReadMethod(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET") || method.equals("HEAD")) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        sendError(exchange, 405, "method_not_allowed");
        exchange.close();
        return false;
    }

    private static ByteRange parseRange(String header, long size) {
        if (header == null) return null;
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || size == 0) {
            throw new IllegalArgumentException("Unsupported range");
        }
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0) throw new IllegalArgumentException("Invalid range");
        String left = value.substring(0, separator).trim();
        String right = value.substring(separator + 1).trim();
        try {
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix range");
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? size - 1 : Long.parseLong(right);
            }
            if (start < 0 || start >= size || end < start) {
                throw new IllegalArgumentException("Unsatisfiable range");
            }
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid range", e);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code) throws IOException {
        byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        commonHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(exchange, status, body);
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);progressed(exchange);
        }
    }

    private static void commonHeaders(Headers headers) {
        headers.set("Server", SERVER_NAME);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
    }

    private static boolean etagMatches(HttpExchange exchange, String value) {
        String request = exchange.getRequestHeaders().getFirst("If-None-Match");
        return request != null && (request.equals("*") || request.equals(quoted(value)));
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static boolean validProjectId(String value) {
        return value.matches("[a-z0-9][a-z0-9._-]{0,63}");
    }

    private static boolean validReleaseId(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static boolean validSemanticVersion(String value) {
        try {
            SemanticVersion.parse(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private record ByteRange(long start, long end) {
    }

    private record SignedPresentation(byte[] payload, String signature) {
    }
}
