package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * One saved upload destination per project, credentials included, so later publishes reach OSS or WebDAV
 * without retyping keys and can be uploaded automatically. The file sits beside the project signing key
 * with the same protection and travels only inside encrypted backups; the browser only receives {@link View}.
 */
public final class ExternalHostingService {
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);
    private static final Set<String> PROVIDERS = Set.of("s3", "webdav");

    /** Everything needed to export and upload again. Never returned to the browser or written to logs. */
    public record Destination(int schemaVersion, String provider, String outputDirectory,
                              String baseUrl, String username, String password,
                              String endpoint, String region, String bucket, String prefix,
                              String accessKeyId, String secretAccessKey, String sessionToken,
                              String addressingStyle, boolean autoUpload, Instant savedAt) {
        public Destination {
            provider = text(provider); outputDirectory = text(outputDirectory); baseUrl = text(baseUrl);
            // Secrets are kept exactly as typed; only addresses and names are trimmed.
            username = text(username); password = password == null ? "" : password; endpoint = text(endpoint);
            region = text(region); bucket = text(bucket); prefix = text(prefix); accessKeyId = text(accessKeyId);
            secretAccessKey = secretAccessKey == null ? "" : secretAccessKey;
            sessionToken = sessionToken == null ? "" : sessionToken;
            addressingStyle = text(addressingStyle).isEmpty() ? "PATH" : addressingStyle.toUpperCase(Locale.ROOT);
        }
        public static Destination webDav(String outputDirectory, String baseUrl, String username, String password) {
            return new Destination(1, "webdav", outputDirectory, baseUrl, username, password,
                    "", "", "", "", "", "", "", "", false, null);
        }
        public static Destination s3(String outputDirectory, String endpoint, String region, String bucket, String prefix,
                                     String accessKeyId, String secretAccessKey, String sessionToken, String addressingStyle) {
            return new Destination(1, "s3", outputDirectory, "", "", "", endpoint, region, bucket, prefix,
                    accessKeyId, secretAccessKey, sessionToken, addressingStyle, false, null);
        }
        /** Identifies where files land, so a change of bucket or address is never mistaken for "already uploaded". */
        String key() {
            return provider.equals("s3")
                    ? "s3 " + endpoint + " " + bucket + " " + prefix + " " + addressingStyle
                    : "webdav " + baseUrl;
        }
        String label() {
            if (!provider.equals("s3")) return baseUrl;
            String host;
            try { host = new URI(endpoint).getHost(); } catch (java.net.URISyntaxException error) { host = null; }
            return bucket + (prefix.isEmpty() ? "" : "/" + prefix) + " · " + (host == null ? endpoint : host);
        }
        S3UploadConfiguration s3Configuration() {
            S3AddressingStyle style;
            try { style = S3AddressingStyle.valueOf(addressingStyle); }
            catch (IllegalArgumentException error) { throw new ManagementException("S3 寻址方式无效"); }
            return new S3UploadConfiguration(URI.create(endpoint), region, bucket, prefix,
                    accessKeyId, secretAccessKey, sessionToken, style);
        }
        WebDavUploadConfiguration webDavConfiguration() {
            return new WebDavUploadConfiguration(URI.create(baseUrl), username, password);
        }
        Destination saved(boolean autoUpload) {
            return new Destination(1, provider, outputDirectory, baseUrl, username, password, endpoint, region, bucket,
                    prefix, accessKeyId, secretAccessKey, "", addressingStyle, autoUpload, Instant.now());
        }
        @Override public String toString() {
            return "Destination[provider=" + provider + ", destination=" + key()
                    + ", password=<redacted>, secretAccessKey=<redacted>, sessionToken=<redacted>]";
        }
        private static String text(String value) { return value == null ? "" : value.trim(); }
    }

    /** What the workbench may show: no password, secret key or session token. */
    public record View(String provider, String destination, String outputDirectory, String username,
                       String endpoint, String region, String bucket, String prefix, String accessKeyId,
                       String addressingStyle, boolean autoUpload, Instant savedAt, AutoUpload autoStatus,
                       String problem) {}

    /** The last automatic attempt; kept apart from the credentials and safe to show. */
    public record AutoUpload(int schemaVersion, String destination, String fingerprint, Instant checkedAt,
                             Instant uploadedAt, String problem) {}

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final StaticDistributionService exports;
    private final StaticDistributionUploader uploader;

    public ExternalHostingService(ManagementPaths paths, ManagementDatabase database, JsonCodec json,
                                  StaticDistributionService exports, StaticDistributionUploader uploader) {
        this.paths = paths; this.database = database; this.json = json; this.exports = exports; this.uploader = uploader;
    }

    private Path savedFile(String projectId) {
        database.requireProject(projectId);
        return paths.keys().resolve(projectId + ".hosting.json");
    }

    private Path statusFile(String projectId) {
        database.requireProject(projectId);
        return paths.playerPrograms().resolve(projectId).resolve("external-auto-upload.json");
    }

    public Optional<Destination> saved(String projectId) {
        Path file = savedFile(projectId);
        try {
            PathSafety.assertSafePathTree(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            if (Files.size(file) > 32 * 1024) throw new IOException("Saved hosting is too large");
            var value = json.read(file, Destination.class);
            if (value == null || value.schemaVersion() != 1 || !PROVIDERS.contains(value.provider()))
                throw new IOException("Invalid saved hosting");
            return Optional.of(value);
        } catch (IOException | RuntimeException error) {
            // No cause: a parser message could quote the stored secret.
            throw new ManagementException("已保存的外部托管设置无法读取；可以删除后重新保存");
        }
    }

    public View view(String projectId) {
        Destination saved;
        try { saved = saved(projectId).orElse(null); }
        catch (ManagementException error) {
            return new View("", "", "", "", "", "", "", "", "", "", false, null, null, error.getMessage());
        }
        if (saved == null) return null;
        var status = status(projectId);
        return new View(saved.provider(), saved.label(), saved.outputDirectory(), saved.username(), saved.endpoint(),
                saved.region(), saved.bucket(), saved.prefix(), saved.accessKeyId(), saved.addressingStyle(),
                saved.autoUpload(), saved.savedAt(),
                status != null && saved.key().equals(status.destination()) ? status : null, null);
    }

    public AutoUpload status(String projectId) {
        Path file = statusFile(projectId);
        try {
            PathSafety.assertSafePathTree(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024) return null;
            var value = json.read(file, AutoUpload.class);
            return value != null && value.schemaVersion() == 1 ? value : null;
        } catch (IOException | RuntimeException error) {
            return null; // Only a cache of the last attempt; losing it means one extra upload.
        }
    }

    /**
     * Exports (unless told not to), uploads and records the delivery. With {@code remember}, the destination and
     * its credentials are saved only after this upload worked, so a mistyped key is never kept.
     */
    public DistributionUploadResult deliver(String projectId, Destination destination, boolean exportFirst,
                                            boolean remember) {
        check(destination, remember);
        try (var upload = uploadLock(projectId)) {
            // Taken before exporting: a publish racing this upload is uploaded again later, never skipped.
            String fingerprint = exports.fingerprint(projectId);
            Path output = Path.of(destination.outputDirectory());
            if (exportFirst) exports.exportProject(projectId, output);
            var delivery = new ExternalDeliveryStatusService(paths, database, json);
            Long revision = delivery.capture(projectId, output);
            var result = destination.provider().equals("s3")
                    ? uploader.uploadS3(output, destination.s3Configuration())
                    : uploader.uploadWebDav(output, destination.webDavConfiguration());
            delivery.uploaded(projectId, revision, destination.provider());
            if (remember) store(projectId, destination.saved(true));
            boolean current = remember || saved(projectId).map(saved -> saved.key().equals(destination.key())).orElse(false);
            if (exportFirst && current) {
                var now = Instant.now();
                record(projectId, new AutoUpload(1, destination.key(), fingerprint, now, now, null));
            }
            return result;
        } catch (IOException error) {
            throw new ManagementException("无法记录外部托管上传结果", error);
        }
    }

    /** Uploads with the saved destination, without asking for the credentials again. */
    public DistributionUploadResult upload(String projectId) {
        var saved = saved(projectId).orElseThrow(() -> new ManagementException("还没有保存外部托管设置"));
        return deliver(projectId, saved, true, false);
    }

    public View setAuto(String projectId, boolean enabled) {
        try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".hosting.lock"))) {
            var saved = saved(projectId).orElseThrow(() -> new ManagementException("还没有保存外部托管设置"));
            writeSaved(projectId, new Destination(1, saved.provider(), saved.outputDirectory(), saved.baseUrl(),
                    saved.username(), saved.password(), saved.endpoint(), saved.region(), saved.bucket(), saved.prefix(),
                    saved.accessKeyId(), saved.secretAccessKey(), "", saved.addressingStyle(), enabled, saved.savedAt()));
        } catch (IOException error) { throw new ManagementException("无法保存自动上传设置", error); }
        return view(projectId);
    }

    public void forget(String projectId) {
        try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".hosting.lock"))) {
            Files.deleteIfExists(savedFile(projectId));
            Files.deleteIfExists(statusFile(projectId));
        } catch (IOException error) { throw new ManagementException("无法删除已保存的外部托管设置", error); }
    }

    /**
     * Uploads when the project's public content changed since the last upload to the saved destination.
     * A failed attempt waits an hour unless something new is published; a busy project is retried next pass.
     */
    public AutoUpload runAutomatic(String projectId) {
        var saved = saved(projectId).orElse(null);
        if (saved == null || !saved.autoUpload()) return status(projectId);
        try (var upload = uploadLock(projectId)) {
            var status = status(projectId);
            String fingerprint = exports.fingerprint(projectId);
            boolean same = status != null && saved.key().equals(status.destination()) && fingerprint.equals(status.fingerprint());
            if (same && (status.problem() == null
                    || status.checkedAt() != null && status.checkedAt().isAfter(Instant.now().minus(RETRY_AFTER_FAILURE))))
                return status;
            String problem;
            try {
                deliver(projectId, saved, true, false);
                return status(projectId);
            } catch (ManagementException | DistributionRetirementException error) {
                if (busy(error)) return status;
                problem = error.getMessage();
            } catch (RuntimeException error) {
                problem = "自动上传没有完成，请在“外部托管”页点击“立即上传”查看原因";
            }
            Instant uploadedAt = status != null && saved.key().equals(status.destination()) ? status.uploadedAt() : null;
            var failed = new AutoUpload(1, saved.key(), fingerprint, Instant.now(), uploadedAt, problem);
            record(projectId, failed);
            return failed;
        } catch (ManagementException error) {
            if (busy(error)) return status(projectId);
            throw error;
        } catch (IOException error) {
            throw new ManagementException("无法记录自动上传结果", error);
        }
    }

    /** One pass over all projects; each project's problem is recorded instead of stopping the others. */
    public void runAutomaticForAll() {
        for (var project : database.listProjects()) {
            try { runAutomatic(project.id()); }
            catch (RuntimeException ignored) { /* Recorded per project when possible; retried on the next pass. */ }
        }
    }

    private ProjectLock uploadLock(String projectId) throws IOException {
        database.requireProject(projectId);
        try { return ProjectLock.acquire(paths.locks().resolve(projectId + ".hosting-upload.lock")); }
        catch (ManagementException error) { throw new ManagementException("另一次外部托管上传正在进行，请稍后重试", error); }
    }

    private static boolean busy(Throwable error) {
        if (ProjectLock.busy(error)) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause.getMessage() != null && cause.getMessage().startsWith("另一次外部托管上传正在进行")) return true;
        return false;
    }

    private void store(String projectId, Destination destination) throws IOException {
        try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".hosting.lock"))) {
            writeSaved(projectId, destination);
        }
    }

    private void writeSaved(String projectId, Destination destination) throws IOException {
        Path file = savedFile(projectId);
        AtomicFiles.write(file, json.writePretty(destination));
        ProjectKeyStore.restrictPermissions(file);
    }

    private void record(String projectId, AutoUpload status) throws IOException {
        try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".hosting.lock"))) {
            AtomicFiles.write(statusFile(projectId), json.writePretty(status));
        }
    }

    private static void check(Destination destination, boolean remember) {
        if (destination == null || !PROVIDERS.contains(destination.provider()))
            throw new ManagementException("外部托管方式无效");
        if (destination.outputDirectory().isEmpty()) throw new ManagementException("请填写静态分发目录");
        if (destination.provider().equals("s3")) {
            for (var field : List.of(Map.entry("S3 API Endpoint", destination.endpoint()), Map.entry("S3 Region", destination.region()),
                    Map.entry("S3 Bucket", destination.bucket()), Map.entry("Access Key ID", destination.accessKeyId())))
                if (field.getValue().isEmpty()) throw new ManagementException("请填写 " + field.getKey());
            checkUrl(destination.endpoint(), "S3 API Endpoint");
            destination.s3Configuration();
        } else {
            if (destination.baseUrl().isEmpty()) throw new ManagementException("请填写 WebDAV / HTTP PUT 地址");
            checkUrl(destination.baseUrl(), "WebDAV / HTTP PUT 地址");
        }
        if (!remember) return;
        if (!Path.of(destination.outputDirectory()).isAbsolute())
            throw new ManagementException("保存设置时，静态分发目录须为完整路径");
        if (destination.provider().equals("s3") && destination.secretAccessKey().isEmpty())
            throw new ManagementException("请填写 Secret Access Key 后再保存");
        if (!destination.sessionToken().isEmpty())
            throw new ManagementException("临时会话令牌会过期，不能保存；请改用长期 AccessKey，建议只授权这个存储桶");
        for (String value : List.of(destination.outputDirectory(), destination.baseUrl(), destination.username(),
                destination.password(), destination.endpoint(), destination.region(), destination.bucket(),
                destination.prefix(), destination.accessKeyId(), destination.secretAccessKey()))
            if (value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
                throw new ManagementException("外部托管设置中的字段过长或包含控制字符");
        URI address = URI.create(destination.provider().equals("s3") ? destination.endpoint() : destination.baseUrl());
        if (address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null)
            throw new ManagementException("保存设置时，地址中不能包含账号密码或查询参数；请把账号密码填在对应栏");
    }

    private static void checkUrl(String value, String label) {
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || !Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)))
                throw new ManagementException(label + " 须为 http 或 https 地址");
        } catch (java.net.URISyntaxException error) {
            throw new ManagementException(label + " 格式无效");
        }
    }
}
