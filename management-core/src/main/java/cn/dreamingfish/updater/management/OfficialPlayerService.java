package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.*;
import java.util.*;

/** Official downloads always happen on the management host, never in the player's process. */
public final class OfficialPlayerService implements AutoCloseable {
    public static final URI CATALOG = URI.create("https://github.com/hanhanyu666/DreamingFish-Updater/releases/latest/download/player-catalog.signed");
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final URI endpoint;
    private final PublicKey key;
    private final HttpClient client;
    private final GitHubReleaseAssets assets;
    private static final String DOWNLOADING = "正在下载官方玩家端程序";
    private static final Duration DOWNLOAD_LIMIT = Duration.ofHours(6), STALL = Duration.ofMinutes(2);
    /** First wait before retrying a transfer that made no progress, doubling up to a minute; tests shorten it. */
    Duration retryPause = Duration.ofSeconds(10);
    /** Links from mainland China to GitHub can drop for minutes, so only this long without any new byte gives up. */
    Duration patience = Duration.ofMinutes(15);

    public OfficialPlayerService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this(paths, database, json, catalog(System.getProperty("dfs.officialPlayerCatalog",
                System.getenv().getOrDefault("DFS_OFFICIAL_PLAYER_CATALOG", ""))), officialKey());
    }

    /**
     * A test pre-release or mirror may replace the address; the catalogue must still carry the pinned
     * official signature, so another address cannot introduce programs the official key did not sign.
     */
    static URI catalog(String override) {
        if (override == null || override.isBlank()) return CATALOG;
        try {
            URI uri = new URI(override.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
                throw new ManagementException("官方玩家端目录地址须为不含账号的 https 地址");
            return uri;
        } catch (java.net.URISyntaxException error) {
            throw new ManagementException("官方玩家端目录地址格式无效");
        }
    }
    OfficialPlayerService(ManagementPaths paths, ManagementDatabase database, JsonCodec json, URI endpoint, PublicKey key) {
        this(paths, database, json, endpoint, key, null);
    }
    OfficialPlayerService(ManagementPaths paths, ManagementDatabase database, JsonCodec json, URI endpoint, PublicKey key,
                          GitHubReleaseAssets assets) {
        this(paths, database, json, endpoint, key, assets, OfficialDownloadProxy.current());
    }
    OfficialPlayerService(ManagementPaths paths, ManagementDatabase database, JsonCodec json, URI endpoint, PublicKey key,
                          GitHubReleaseAssets assets, ProxySelector proxy) {
        this.paths = paths; this.database = database; this.json = json; this.endpoint = endpoint; this.key = key;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).proxy(proxy).build();
        this.assets = assets == null ? new GitHubReleaseAssets(client, json) : assets;
    }
    private static PublicKey officialKey() {
        try (var input = OfficialPlayerService.class.getResourceAsStream("official-player-public-key.txt")) {
            if (input == null) throw new ManagementException("官方玩家端签名公钥缺失，请重新安装管理端");
            return CryptoSupport.decodePublicKey(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (IOException error) { throw new ManagementException("无法读取官方玩家端公钥", error); }
    }

    public OfficialPlayerCatalog check(boolean refresh) {
        Path cache = paths.playerPrograms().resolve("official-catalog.signed");
        try (ProjectLock lock = ProjectLock.acquire(paths.locks().resolve("official-catalog.lock"))) {
            PathSafety.assertSafePathTree(cache);
            if (!refresh && Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                    && Files.getLastModifiedTime(cache).toInstant().isAfter(Instant.now().minus(Duration.ofHours(6))))
                return verify(readLimited(cache, 2 * 1024 * 1024));
            ApplicationTasks.progress("正在检查官方玩家端版本", 0, 1);
            byte[] bytes = getBytes(endpoint, 2 * 1024 * 1024);
            var catalog = verify(bytes);
            if (Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) {
                var old = verify(readLimited(cache, 2 * 1024 * 1024));
                if (catalog.createdAt().isBefore(old.createdAt())) throw new ManagementException("官方目录比本机已验证目录更旧");
                for (var prior : old.releases()) for (var next : catalog.releases()) if (prior.platform().equals(next.platform())) {
                    int order = SemanticVersion.parse(next.version()).compareTo(SemanticVersion.parse(prior.version()));
                    if (order < 0 || order == 0 && !prior.equals(next)) throw new ManagementException("官方玩家程序降级或同版本内容发生改变");
                }
            }
            AtomicFiles.write(cache, bytes);
            return catalog;
        } catch (IOException error) { throw new ManagementException("无法检查官方玩家端；已有项目更新不受影响", error); }
    }

    public StoredPlayerProgram prepare(String projectId, String platform, String version, String archiveHash,
                                       String managementVersion) {
        database.requireProject(projectId);
        var release = check(false).releases().stream().filter(r -> r.platform().equals(platform)
                && r.version().equals(version) && r.archiveSha256().equals(archiveHash)).findFirst()
                .orElseThrow(() -> new ManagementException("官方候选版本已变化，请重新检查后选择"));
        if (SemanticVersion.parse(managementVersion).compareTo(SemanticVersion.parse(release.minimumManagementVersion())) < 0
                || !ProtocolConstants.RELEASE_CAPABILITIES.containsAll(release.requiredCapabilities()))
            throw new ManagementException("当前管理端不能导入这个玩家程序，请先升级管理端");
        Path directory = paths.temporary().resolve("official-player-" + UUID.randomUUID());
        var programs = new PlayerProgramService(paths, database, json);
        try (var budget = new StorageService(paths, database, json).reserve(release.archiveSize(), 0)) {
            var existing = prepared(programs, projectId, platform, version);
            if (existing.isPresent()) { verifyPrepared(existing.get(), release); return existing.get(); }
            PathSafety.createSafeDirectories(directory);
            Path zip = directory.resolve("player.zip");
            // The project stays usable while a slow download runs; only storing the program takes its lock.
            // The owner may stop the download; a stop accepted meanwhile ends the task before anything is stored.
            ApplicationTasks.stoppable(true);
            download(release, zip, budget);
            ApplicationTasks.stoppable(false);
            if (Files.size(zip) != release.archiveSize() || !CryptoSupport.sha256(zip).equals(release.archiveSha256()))
                throw new ManagementException("官方程序包大小或哈希不符，未批准任何新版本");
            try (var lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
                existing = prepared(programs, projectId, platform, version);
                if (existing.isPresent()) { verifyPrepared(existing.get(), release); return existing.get(); }
                Path bundle = directory.resolve("bundle");
                // The deployment ZIP also contains Agent/bootstrap files outside the program payload.
                // Reserve its bounded full expansion instead of estimating it from program files.
                try (var extraction = new StorageService(paths, database, json).reserve(expandedSize(zip), 0)) {
                    PlayerPackageService.extract(zip, bundle, extraction);
                }
                var source = PlayerProgramService.resolveSource(bundle, "", "");
                if (!source.version().equals(version) || !source.launcher().equals(release.launchPath()))
                    throw new ManagementException("官方程序包内的版本与签名目录不一致");
                verifyFiles(source.root(), release);
                ApplicationTasks.committing("正在保存候选程序；完成后仍需服主批准");
                return programs.prepare(projectId, platform, version, source.root(), source.launcher(), release.minimumBootstrapVersion(), release.files());
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ManagementException("官方程序准备已中断", error);
        } catch (IOException error) { throw new ManagementException("无法准备官方玩家端程序", error);
        } finally { try { AtomicFiles.deleteRecursively(directory); } catch (IOException ignored) { /* Private staging is retryable. */ } }
    }

    private static Optional<StoredPlayerProgram> prepared(PlayerProgramService programs, String projectId,
                                                          String platform, String version) {
        return programs.list(projectId, platform).stream().filter(p -> p.version().equals(version)).findFirst();
    }

    /**
     * Without a proxy a mainland China host may need hours and its connection may drop or stall. Each break
     * resumes from the bytes already saved, the task reports progress every 256 KiB, and only a transfer that
     * receives nothing for {@link #patience} gives up. The caller still checks the size and signed hash of the archive.
     */
    private void download(OfficialPlayerCatalog.Release release, Path zip, StorageService.Lease budget)
            throws IOException, InterruptedException {
        long size = release.archiveSize(), start = budget.checkpoint(), end = System.nanoTime() + DOWNLOAD_LIMIT.toNanos();
        long done = 0, reported = 0, lastByte = System.nanoTime();
        int stalled = 0;
        IOException dropped = null;
        ApplicationTasks.progress(DOWNLOADING, 0, size);
        PathSafety.assertSafePathTree(zip);
        try (var out = FileChannel.open(zip, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[128 * 1024];
            while (done < size) {
                long left = end - System.nanoTime(), before = done;
                if (left <= 0) throw new ManagementException("官方程序 6 小时内没有下载完成；可以在自己电脑下载 ZIP 后手动上传");
                try (var response = assets.open(URI.create(release.downloadUrl()), Duration.ofNanos(left), STALL, done)) {
                    if (response == null) throw new ManagementException("官方程序下载失败，HTTP 404");
                    int status = response.statusCode();
                    if (status == 200 && done > 0) { out.truncate(0); budget.rewind(start); done = before = 0; } // the host ignored the range
                    else if (status >= 500) throw new IOException("HTTP " + status);
                    else if (status == 206 ? !response.header("Content-Range").startsWith("bytes " + done + "-") : status != 200)
                        throw new ManagementException("官方程序下载失败，HTTP " + status);
                    for (int n; (n = response.body().read(buffer)) >= 0; ) {
                        if (n > size - done) throw new ManagementException("官方程序包大小或哈希不符，未批准任何新版本");
                        budget.charge(n);
                        for (var bytes = ByteBuffer.wrap(buffer, 0, n); bytes.hasRemaining(); ) out.write(bytes);
                        done += n; lastByte = System.nanoTime();
                        if (done - reported >= 256 * 1024 || done == size) { reported = done; ApplicationTasks.progress(DOWNLOADING, done, size); }
                    }
                } catch (IOException broken) {
                    if (Thread.interrupted()) throw new InterruptedException("official download stopped");
                    dropped = broken;
                }
                if (done > before) stalled = 0;
                else if (System.nanoTime() - lastByte >= patience.toNanos())
                    throw new ManagementException("官方程序下载 " + patience.toMinutes() + " 分钟没有任何进展，请稍后重试，或在自己电脑下载 ZIP 后手动上传", dropped);
                else Thread.sleep(Math.min(60_000, retryPause.toMillis() << Math.min(stalled++, 6)));
            }
            out.force(true);
        }
    }

    private static long expandedSize(Path zip) throws IOException {
        long total = 0; int entries = 0;
        try (var archive = new java.util.zip.ZipFile(zip.toFile())) {
            var all = archive.entries();
            while (all.hasMoreElements()) {
                var entry = all.nextElement();
                if (++entries > 20_000 || entry.getSize() < 0) throw new ManagementException("官方 ZIP 的文件数量或大小无效");
                total = Math.addExact(total, entry.getSize());
                if (total > 4L * 1024 * 1024 * 1024) throw new ManagementException("官方 ZIP 解压后超过 4 GiB");
            }
        }
        return total;
    }

    private void verifyPrepared(StoredPlayerProgram program, OfficialPlayerCatalog.Release release) {
        try {
            var manifest = json.read(program.manifestPath(), PlayerProgramManifest.class);
            if (!new HashSet<>(manifest.files()).equals(new HashSet<>(release.files()))
                    || !manifest.minimumBootstrapVersion().equals(release.minimumBootstrapVersion())
                    || !manifest.launchPath().equals(release.launchPath())) throw new ManagementException("已有同版本程序与官方签名内容不同，不能覆盖");
        } catch (IOException error) { throw new ManagementException("无法读取已有候选程序", error); }
    }

    private static void verifyFiles(Path root, OfficialPlayerCatalog.Release release) throws IOException {
        Set<String> expected = new HashSet<>();
        for (var file : release.files()) {
            Path path = PathSafety.resolveInside(root, file.path());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.size()
                    || !CryptoSupport.sha256(path).equals(file.sha256())) throw new ManagementException("官方程序文件验证失败：" + file.path());
            expected.add(file.path());
        }
        try (var stream = Files.walk(root)) {
            for (Path file : stream.toList()) {
                PathSafety.assertSafePathTree(file);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && !expected.contains(root.relativize(file).toString().replace('\\', '/')))
                    throw new ManagementException("官方程序目录出现签名清单之外的文件");
            }
        }
    }

    private OfficialPlayerCatalog verify(byte[] bytes) {
        var signed = SignedDocument.decode(bytes, 2 * 1024 * 1024);
        if (!CryptoSupport.verify(signed.payload(), Base64.getDecoder().decode(signed.signature()), key))
            throw new ManagementException("官方玩家端目录签名无效，未采用任何新程序");
        var catalog = json.read(signed.payload(), OfficialPlayerCatalog.class);
        catalog.validate();
        return catalog;
    }
    private byte[] getBytes(URI uri, int maximum) throws IOException {
        try {
            // One retry: links from mainland China to GitHub's storage often fail once and then answer.
            for (int attempt = 1; ; attempt++) {
                try (var response = assets.open(uri, Duration.ofSeconds(30))) {
                    if (response == null || response.statusCode() == 404) throw new ManagementException("当前官方 Release 尚未提供玩家端签名目录；可继续手动上传发行 ZIP，项目原有更新不受影响");
                    if (response.statusCode() != 200) throw new ManagementException("官方玩家端目录暂不可用，HTTP " + response.statusCode());
                    byte[] bytes = response.body().readNBytes(maximum + 1);
                    if (bytes.length > maximum) throw new ManagementException("官方玩家端目录超过大小限制");
                    return bytes;
                } catch (IOException failed) { if (attempt >= 2) throw failed; }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ManagementException("官方版本检查已中断", error); }
    }
    private static byte[] readLimited(Path file, int maximum) throws IOException {
        if (Files.size(file) > maximum) throw new ManagementException("官方玩家端缓存过大");
        return Files.readAllBytes(file);
    }
    @Override public void close() { client.close(); }

}
