package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Lock order: shared pack -> instance -> game. Peer game locks are held through commit. */
final class PackUpdateLock implements AutoCloseable {
    private final InstanceUpdateLock pack;
    private final List<GameUpdateLock> peers = new ArrayList<>();
    private boolean peerGameRunning;
    private PackUpdateLock(InstanceUpdateLock pack) { this.pack = pack; }
    private record Registry(int schemaVersion, String id, String projectId, List<String> instances) {}

    static PackUpdateLock acquire(Path instance, ProjectBinding binding) {
        if (binding.packRoot() == null) return new PackUpdateLock(null);
        PackUpdateLock held = null;
        try {
            var local = ContentRoots.binding(instance);
            if (!local.projectId().equals(binding.projectId()) || !Objects.equals(local.packRoot(), binding.packRoot()))
                throw new IOException("本机更新目录授权与项目配置不同");
            Path root = ContentRoots.root(instance, binding), lock = root.resolve(".dreamingfish-pack-root.lock");
            PathSafety.assertSafePathTree(lock);
            held = new PackUpdateLock(InstanceUpdateLock.acquire(lock));
            ContentRoots.initialize(instance, binding);
            Path registryFile = root.resolve(".dreamingfish-pack-instances.json");
            PathSafety.assertSafePathTree(registryFile);
            Set<String> registered = new TreeSet<>(); var json = new JsonCodec();
            if (Files.exists(registryFile, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(registryFile) > 64 * 1024) throw new IOException("共享目录实例索引过大");
                var registry = json.read(registryFile, Registry.class);
                if (registry.schemaVersion() != 1 || !registry.id().equals(binding.packRoot().id())
                        || !registry.projectId().equals(binding.projectId()) || registry.instances() == null || registry.instances().size() > 128)
                    throw new IOException("共享目录实例索引身份无效");
                registered.addAll(registry.instances());
            }
            String self = root.relativize(instance.toAbsolutePath().normalize()).toString().replace('\\', '/');
            PathSafety.normalizeManifestPath(self);
            for (String relative : registered) {
                Path peer = PathSafety.resolveInside(root, relative);
                if (peer.equals(instance.toAbsolutePath().normalize()) || !Files.exists(peer, LinkOption.NOFOLLOW_LINKS)) continue;
                var peerBinding = ContentRoots.binding(peer);
                if (!peerBinding.projectId().equals(binding.projectId()) || !Objects.equals(peerBinding.packRoot(), binding.packRoot())
                        || !ContentRoots.root(peer, peerBinding).equals(root))
                    throw new IOException("共享目录中的实例绑定已变化，请保留目录索引后核对");
                Path marker = peer.resolve(".dreamingfish-bootstrap/game.lock"); PathSafety.assertSafePathTree(marker);
                var game = GameUpdateLock.tryAcquire(marker);
                if (game == null) held.peerGameRunning = true;
                else held.peers.add(game);
            }
            registered.add(self);
            if (registered.size() > 128) throw new IOException("共享整合包实例超过 128 个");
            AtomicFileSupport.write(registryFile, json.writePretty(new Registry(1, binding.packRoot().id(), binding.projectId(), List.copyOf(registered))));
            return held;
        } catch (IOException | RuntimeException error) {
            if (held != null) held.close();
            if (error instanceof UpdateException update) throw update;
            throw new UpdateException(UpdateErrorCode.PATH_UNSAFE, "无法确认外层更新目录：" + error.getMessage(), error);
        }
    }
    void requireWritable() {
        if (peerGameRunning) throw new UpdateException(UpdateErrorCode.GAME_RUNNING, "同一整合包的另一个实例正在运行，请关闭后再更新或恢复外层文件");
    }
    public void close() {
        for (int i = peers.size() - 1; i >= 0; i--) peers.get(i).close();
        if (pack != null) pack.close();
    }
}
