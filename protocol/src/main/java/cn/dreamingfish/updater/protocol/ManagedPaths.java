package cn.dreamingfish.updater.protocol;

import java.util.Locale;

/** Case-insensitive helpers for normalized manifest paths. */
public final class ManagedPaths {
    private ManagedPaths() {
    }

    public static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** True when {@code path} is strictly below {@code directory}. */
    public static boolean isBelow(String path, String directory) {
        return fold(path).startsWith(fold(directory) + "/");
    }

    /** True when {@code path} equals {@code directory} or is below it. */
    public static boolean isSameOrBelow(String path, String directory) {
        String folded = fold(path);
        String root = fold(directory);
        return folded.equals(root) || folded.startsWith(root + "/");
    }

    public static String topLevel(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(0, slash);
    }

    public static boolean isModJar(String path) {
        String folded = fold(path);
        return folded.startsWith("mods/") && folded.endsWith(".jar");
    }
}
