package cn.dreamingfish.updater.protocol;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Keeps ordinary game paths unchanged and resolves explicitly granted pack paths. */
public final class ContentRoots {
    public static final String PREFIX = "@pack/";
    public static final String MARKER = ".dreamingfish-pack-root.json";
    private static final JsonCodec JSON = new JsonCodec();
    private ContentRoots() {}
    public record Marker(int schemaVersion, String id, String projectId) {}

    public static boolean isPackPath(String path) { return ManagedPaths.fold(path).startsWith(PREFIX); }

    /** A grant accepted from a signed release, kept beside the deployment binding. */
    public record Grant(int schemaVersion, String projectId, PackRootSpec packRoot) {}
    public static final String GRANT = ".dreamingfish-bootstrap/pack-root-grant.json";

    /** The local binding, completed with an accepted remote grant when the deployment has none. */
    public static ProjectBinding binding(Path instance) throws IOException {
        Path file = instance.resolve(".dreamingfish-bootstrap/project-binding.json");
        PathSafety.assertSafePathTree(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024)
            throw new IOException("Missing or invalid local project binding");
        return withGrant(instance, JSON.read(file, ProjectBinding.class));
    }

    public static ProjectBinding withGrant(Path instance, ProjectBinding binding) throws IOException {
        if (binding.packRoot() != null) return binding;
        Path file = instance.resolve(GRANT);
        PathSafety.assertSafePathTree(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return binding;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024)
            throw new IOException("外层目录授权记录无效，请保留原文件后核对");
        try {
            Grant grant = JSON.read(file, Grant.class);
            if (grant == null || grant.schemaVersion() != 1 || grant.packRoot() == null
                    || !Objects.equals(grant.projectId(), binding.projectId()))
                throw new IOException("外层目录授权记录属于其他项目");
            return binding.withPackRoot(grant.packRoot());
        } catch (ProtocolException error) {
            throw new IOException("外层目录授权记录损坏，请保留原文件后核对", error);
        }
    }

    /** Why this grant cannot be used here, or null when the folders match and the identity is free or ours. */
    public static String unusable(Path instance, ProjectBinding binding) {
        if (binding.packRoot() == null) return "这台电脑尚未授权更新整合包外层文件";
        try {
            Path root = root(instance, binding);
            if (Files.exists(root.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) verifyMarker(root, binding);
            return null;
        } catch (IOException | ProtocolException error) {
            return error.getMessage();
        }
    }

    public static Path root(Path instance, ProjectBinding binding) throws IOException {
        PackRootSpec spec = binding.packRoot();
        if (spec == null) throw new IOException("这台电脑尚未保存外层目录授权；服主启用外层目录并发布后，玩家下次在线检查时自动保存");
        Path normalized = instance.toAbsolutePath().normalize();
        Path root = normalized.resolve(spec.relativeRoot()).normalize();
        String name = root.getFileName() == null ? "" : root.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(root) || normalized.equals(root) || root.getNameCount() < 2
                || root.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
                || Set.of("desktop", "documents", "downloads", "users", "windows", "program files").contains(name))
            throw new IOException("外层更新起始目录过宽，请选择专属整合包目录");
        PathSafety.assertSafePathTree(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("整合包外层目录不存在");
        List<String> actual = new ArrayList<>();
        for (Path segment : root.relativize(normalized)) actual.add(segment.toString());
        if (!spec.matchesLayout(actual))
            throw new IOException("玩家的目录结构是 " + String.join("/", actual) + "，与服主设置的 "
                    + String.join("/", spec.layout()) + " 不同，已跳过外层文件");
        return root;
    }

    public static Path resolve(Path instance, String manifestPath) throws IOException {
        String path = PathSafety.normalizeManifestPath(ConfigPatches.targetPath(manifestPath));
        if (!isPackPath(path)) return PathSafety.resolveInside(instance, path);
        var binding = binding(instance);
        var spec = binding.packRoot();
        if (spec == null || !path.startsWith(spec.prefix())) throw new IOException("外层文件的目录身份与这台电脑记录的授权不符");
        Path root = root(instance, binding);
        verifyMarker(root, binding);
        String relative = path.substring(spec.prefix().length());
        validatePackPath(relative);
        Path target = PathSafety.resolveInside(root, relative);
        if (target.startsWith(instance.toAbsolutePath().normalize())) throw new IOException("外层文件不能再次接管游戏实例");
        for (Path current = target.getParent(); current != null && current.startsWith(root) && !current.equals(root); current = current.getParent()) {
            if (Files.isDirectory(current.resolve(".dreamingfish-bootstrap"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("外层文件不能接管其他游戏实例");
        }
        return target;
    }

    public static String relative(Path instance, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize(), game = instance.toAbsolutePath().normalize();
        if (normalized.startsWith(game)) return game.relativize(normalized).toString().replace('\\', '/');
        var binding = binding(instance); Path root = root(instance, binding); verifyMarker(root, binding);
        if (!normalized.startsWith(root)) throw new IOException("File is outside approved content roots");
        return binding.packRoot().prefix() + root.relativize(normalized).toString().replace('\\', '/');
    }

    public static void validatePackPath(String relative) {
        String path = PathSafety.normalizeManifestPath(relative);
        String folded = ManagedPaths.fold(path);
        for (String segment : folded.split("/")) {
            if (Set.of(".minecraft", ".dreamingfish-bootstrap", "dreamingfishupdater", "saves", "screenshots",
                    "accounts.json", "launcher_accounts.json", "hmcl_accounts.json", "credentials.json",
                    "management-settings.json", "management-web-auth.json", MARKER, ".dreamingfish-pack-root.lock",
                    ".dreamingfish-pack-instances.json").contains(segment)
                    || segment.endsWith(".pk8") || segment.endsWith(".key"))
                throw new ProtocolException("外层文件路径属于受保护的游戏、账户或更新器数据：" + path);
        }
    }

    public static void verifyMarker(Path root, ProjectBinding binding) throws IOException {
        Path file = root.resolve(MARKER); PathSafety.assertSafePathTree(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024)
            throw new IOException("整合包目录身份文件缺失，请重新确认本机目录授权");
        try {
            Marker marker = JSON.read(file, Marker.class);
            if (marker == null || marker.schemaVersion() != 1 || !Objects.equals(marker.id(), binding.packRoot().id())
                    || !Objects.equals(marker.projectId(), binding.projectId()))
                throw new IOException("整合包目录身份不匹配，已停止外层更新");
        } catch (ProtocolException error) {
            throw new IOException("整合包目录身份文件损坏，请保留原文件后重新确认本机目录授权", error);
        }
    }

    /** Called only for the grant already present in the local deployment binding. */
    public static void initialize(Path instance, ProjectBinding binding) throws IOException {
        if (binding.packRoot() == null) return;
        Path root = root(instance, binding), file = root.resolve(MARKER);
        PathSafety.assertSafePathTree(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = JSON.writePretty(new Marker(1, binding.packRoot().id(), binding.projectId()));
            try { Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException ignored) { /* Another instance initialized the same identity. */ }
        }
        verifyMarker(root, binding);
    }
}
