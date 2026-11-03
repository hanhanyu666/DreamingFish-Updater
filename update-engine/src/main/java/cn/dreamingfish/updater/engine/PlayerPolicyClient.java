package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.PublicKey;

/** Signed project authorization; 404 is legacy only before any policy was accepted. */
final class PlayerPolicyClient {
    private final JsonCodec json = new JsonCodec();
    private Path state(UpdateRequest request) { return request.playerHome().resolve("state/player-update-policy.json"); }

    PlayerUpdatePolicy fetch(UpdateRequest request, PublicKey key) {
        var uri = ManifestFetcher.endpoint(request.binding(), "v1/projects/" + request.binding().projectId() + "/player/policy.signed");
        var http = HttpRequest.newBuilder(uri).GET().timeout(request.requestTimeout())
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").build();
        try {
            var response = HttpTransfer.send(ManifestFetcher.client(request), http, request.requestTimeout(), request.cancellationToken());
            try (var input = response.body()) {
                if (response.statusCode() == 404 && !Files.exists(state(request), LinkOption.NOFOLLOW_LINKS)) return null;
                if (response.statusCode() != 200) throw new IOException("Player update policy returned HTTP " + response.statusCode());
                var signed = SignedDocument.decode(input.readNBytes(64 * 1024 + SignedDocument.MAX_OVERHEAD + 1), 64 * 1024);
                LocalInstallationStore.verifySignature(signed.payload(), signed.signature(), key);
                var policy = json.read(signed.payload(), PlayerUpdatePolicy.class);
                policy.validate(request.binding().projectId());
                validatePrevious(request, policy);
                return policy;
            }
        } catch (UpdateException error) { throw error;
        } catch (ProtocolException error) { throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST, "玩家程序更新策略无效", error);
        } catch (IOException error) { throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE, "暂时无法确认服主批准的玩家程序版本", error);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new UpdateException(UpdateErrorCode.CANCELLED, "程序更新检查已取消", error); }
    }

    void accept(UpdateRequest request, PlayerUpdatePolicy policy) {
        if (policy == null) return;
        validatePrevious(request, policy);
        try { AtomicFileSupport.write(state(request), json.writePretty(policy)); }
        catch (IOException error) { throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "无法保存程序更新授权记录", error); }
    }

    private void validatePrevious(UpdateRequest request, PlayerUpdatePolicy policy) {
        Path file = state(request);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            PathSafety.assertSafePathTree(file);
            if (Files.size(file) > 64 * 1024) throw new IOException("Policy state is too large");
            var old = json.read(file, PlayerUpdatePolicy.class);
            old.validate(request.binding().projectId());
            if (policy.revision() < old.revision()
                    || (policy.revision() == old.revision() && !policy.equals(old)))
                throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED, "程序更新授权比本机已接受的授权更旧或内容已改变");
            old.targets().forEach((platform, target) -> {
                var next = policy.targets().get(platform);
                if (next != null && (SemanticVersion.parse(next.version()).compareTo(SemanticVersion.parse(target.version())) < 0
                        || next.version().equals(target.version()) && !next.manifestSha256().equals(target.manifestSha256())))
                    throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED, "已批准程序发生降级或同版本内容替换");
            });
        } catch (IOException | ProtocolException error) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "程序更新授权记录不可用，请保留原文件", error);
        }
    }
}
