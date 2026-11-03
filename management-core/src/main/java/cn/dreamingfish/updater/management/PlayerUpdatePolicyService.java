package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One atomically signed document is the authority for approval and pause. */
public final class PlayerUpdatePolicyService {
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public PlayerUpdatePolicyService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths; this.database = database; this.json = json;
    }

    private Path file(String projectId) {
        database.requireProject(projectId);
        return paths.playerPrograms().resolve(projectId).resolve("update-policy.signed");
    }

    public PlayerUpdatePolicy read(String projectId) {
        var project = database.requireProject(projectId);
        Path file = file(projectId);
        try {
            PathSafety.assertSafePathTree(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                // Preserve pre-policy installations without automatically approving candidates.
                Map<String, PlayerUpdatePolicy.Target> targets = new TreeMap<>();
                var programs = new PlayerProgramService(paths, database, json);
                for (String platform : programs.listPlatforms(projectId)) {
                    programs.legacyLatest(projectId, platform).ifPresent(program -> targets.put(platform,
                            new PlayerUpdatePolicy.Target(program.version(), program.manifestSha256())));
                }
                return new PlayerUpdatePolicy(1, projectId, 0, !targets.isEmpty(), targets, project.createdAt());
            }
            if (Files.size(file) > 64 * 1024) throw new ManagementException("玩家程序更新策略过大");
            SignedDocument signed = SignedDocument.decode(Files.readAllBytes(file), 64 * 1024);
            if (!CryptoSupport.verify(signed.payload(), Base64.getDecoder().decode(signed.signature()),
                    CryptoSupport.decodePublicKey(project.publicKey()))) {
                throw new ManagementException("玩家程序更新策略签名无效");
            }
            var policy = json.read(signed.payload(), PlayerUpdatePolicy.class);
            policy.validate(projectId);
            return policy;
        } catch (IOException | ProtocolException | IllegalArgumentException error) {
            throw new ManagementException("无法读取玩家程序更新策略；请保留原文件后核对", error);
        }
    }

    public byte[] signed(String projectId) {
        var policy = read(projectId);
        byte[] payload = json.writePretty(policy);
        var project = database.requireProject(projectId);
        return new SignedDocument(payload, Base64.getEncoder().encodeToString(
                CryptoSupport.sign(payload, new ProjectKeyStore(paths).load(project)))).encode();
    }

    public PlayerUpdatePolicy setEnabled(String projectId, boolean enabled, long expectedRevision) {
        database.requireProject(projectId);
        try (ProjectLock lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            var previous = read(projectId);
            requireRevision(previous, expectedRevision);
            if (enabled && previous.targets().isEmpty()) throw new ManagementException("请先批准一个玩家端程序版本");
            if (previous.enabled() == enabled) return previous;
            return save(previous, enabled, previous.targets());
        } catch (IOException error) { throw new ManagementException("无法保存玩家程序更新开关", error); }
    }

    public PlayerUpdatePolicy approve(String projectId, String platform, String version,
                                      String expectedHash, long expectedRevision) {
        database.requireProject(projectId);
        try (ProjectLock lock = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            var previous = read(projectId);
            requireRevision(previous, expectedRevision);
            var program = new PlayerProgramService(paths, database, json).read(projectId, platform, version);
            if (!program.manifestSha256().equals(expectedHash)) throw new ManagementException("待批准程序已经变化，请重新检查");
            var old = previous.targets().get(platform);
            if (old != null && SemanticVersion.parse(version).compareTo(SemanticVersion.parse(old.version())) < 0)
                throw new ManagementException("不能降低已批准的玩家程序版本，请发布更高版本的修正版");
            if (old != null && old.version().equals(version) && !old.manifestSha256().equals(expectedHash))
                throw new ManagementException("相同玩家程序版本不能替换内容");
            Map<String, PlayerUpdatePolicy.Target> targets = new TreeMap<>(previous.targets());
            targets.put(platform, new PlayerUpdatePolicy.Target(version, expectedHash));
            if (previous.enabled() && targets.equals(previous.targets())) return previous;
            return save(previous, true, targets);
        } catch (IOException error) { throw new ManagementException("无法批准玩家端程序", error); }
    }

    private static void requireRevision(PlayerUpdatePolicy policy, long expected) {
        if (expected >= 0 && expected != policy.revision()) throw new ManagementException("更新策略已被修改，请刷新后重新确认");
    }

    private PlayerUpdatePolicy save(PlayerUpdatePolicy old, boolean enabled,
                                    Map<String, PlayerUpdatePolicy.Target> targets) throws IOException {
        var next = new PlayerUpdatePolicy(1, old.projectId(), Math.addExact(old.revision(), 1), enabled, targets, Instant.now());
        next.validate(old.projectId());
        byte[] payload = json.writePretty(next);
        var project = database.requireProject(old.projectId());
        var signed = new SignedDocument(payload, Base64.getEncoder().encodeToString(
                CryptoSupport.sign(payload, new ProjectKeyStore(paths).load(project))));
        AtomicFiles.write(file(old.projectId()), signed.encode());
        return next;
    }
}
