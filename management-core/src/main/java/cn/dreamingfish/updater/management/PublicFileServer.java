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
import java.util.concurrent.Executors;

public final class PublicFileServer implements AutoCloseable {
    private static final String SERVER_NAME = "DreamingFishUpdateSystem/0.1";
    private static final int BUFFER_SIZE = 128 * 1024;

    private final ManagementDatabase database;
    private final ObjectStore objects;
    private final PlayerProgramService playerPrograms;
    private final ProjectKeyStore projectKeys;
    private final JsonCodec json;
    private final HttpServer server;
    private final ExecutorService executor;

    public PublicFileServer(ManagementDatabase database, ObjectStore objects,
                            InetSocketAddress address) {
        this.database = database;
        this.objects = objects;
        this.json = new JsonCodec();
        this.playerPrograms = new PlayerProgramService(objects.paths(), database, json);
        this.projectKeys = new ProjectKeyStore(objects.paths());
        try {
            server = HttpServer.create(address, 128);
        } catch (IOException e) {
            throw new ManagementException("Unable to bind public HTTP listener " + address, e);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/healthz", this::handleHealth);
        server.createContext("/v1/projects/", this::handleProject);
        server.createContext("/v1/objects/sha256/", this::handleObject);
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!allowReadMethod(exchange)) return;
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
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
            if (segments.length == 2 && validProjectId(segments[0]) && segments[1].equals("latest")) {
                Optional<StoredRelease> latest = database.latestRelease(segments[0]);
                if (latest.isEmpty()) {
                    sendError(exchange, 404, "release_not_found");
                    return;
                }
                sendManifest(exchange, latest.get(), false);
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
            if (segments.length == 6 && validProjectId(segments[0])
                    && segments[1].equals("player") && validProjectId(segments[2])
                    && segments[3].equals("versions") && validSemanticVersion(segments[4])
                    && segments[5].equals("manifest")) {
                sendPlayerProgramManifest(exchange,
                        playerPrograms.read(segments[0], segments[2], segments[4]), true);
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
        PlayerPresentation presentation = new PlayerPresentation(
                ProtocolConstants.PLAYER_PRESENTATION_SCHEMA_VERSION,
                project.id(),
                project.branding());
        ManifestValidator.validatePlayerPresentation(presentation);
        byte[] body = json.writePretty(presentation);
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
        String signature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(body, projectKeys.load(project)));
        headers.set(ProtocolConstants.SIGNATURE_HEADER, signature);
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

    private static void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
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
}
