package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;

/** Keep small signed patch instructions after disposable download objects are cleaned. */
final class ConfigPatchObjects {
    private ConfigPatchObjects() {}
    static Path retained(EnginePaths paths, String hash) { return paths.state().resolve("config-patches/" + hash + ".json"); }
    static byte[] load(EnginePaths paths, String hash, long size) throws IOException {
        if (size > 256 * 1024) throw new IOException("配置合并规则过大");
        for (Path file : java.util.List.of(retained(paths, hash), paths.cacheObject(hash))) {
            PathSafety.assertSafePathTree(file);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == size
                    && CryptoSupport.sha256(file).equals(hash)) return Files.readAllBytes(file);
        }
        return null;
    }
    static void retain(EnginePaths paths, String hash, byte[] bytes) throws IOException {
        if (bytes.length > 256 * 1024 || !CryptoSupport.sha256(bytes).equals(hash)) throw new IOException("配置合并规则验证失败");
        AtomicFileSupport.write(retained(paths, hash), bytes);
    }
}
