package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads a standard modpack directory the way listing and scanning will, so a directory that
 * cannot be published is refused before a project points at it. Failures name the entry and
 * the reason in the words the admin sees.
 */
public final class SourceDirectoryCheck {
    private static final String ADVICE =
            "整合包目录只需存放要发给玩家的 mods、config 等内容，建议新建一个专用文件夹";

    private SourceDirectoryCheck() {
    }

    static void requireReadable(Path source) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (dir.equals(source)) return FileVisitResult.CONTINUE;
                    requireName(source, dir);
                    // Junctions report as directories; real folders resolve to themselves.
                    if (attrs.isOther() && isAlias(dir)) throw linked(source, dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    requireName(source, file);
                    if (attrs.isSymbolicLink()) throw linked(source, file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    throw unreadable(source, error);
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                    if (error != null) throw unreadable(source, error);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException error) {
            throw unreadable(source, error);
        }
    }

    /** Explains why an entry below {@code source} could not be read. */
    public static ManagementException unreadable(Path source, Throwable error) {
        Throwable cause = error instanceof UncheckedIOException unchecked ? unchecked.getCause() : error;
        Path file = cause instanceof FileSystemException fs && fs.getFile() != null
                ? Path.of(fs.getFile()) : source;
        return new ManagementException("无法读取整合包目录中的“" + display(source, file) + "”："
                + reason(cause) + "。" + ADVICE, cause);
    }

    /** Explains that an entry below {@code source} is a link the updater will not follow. */
    public static ManagementException linked(Path source, Path entry) {
        return new ManagementException("整合包目录中的“" + display(source, entry)
                + "”是快捷链接或目录链接，更新器不会跟随链接。请换成真实的文件或文件夹。" + ADVICE);
    }

    private static void requireName(Path source, Path entry) {
        String relative = source.relativize(entry).toString().replace('\\', '/');
        try {
            PathSafety.normalizeManifestPath(relative);
        } catch (ProtocolException invalid) {
            throw new ManagementException("整合包目录中的“" + relative
                    + "”名称不能发布给玩家（以空格或句点结尾，或含有 Windows 不允许的字符）。请重命名或移出它", invalid);
        }
    }

    private static boolean isAlias(Path dir) throws IOException {
        return !dir.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(dir.toRealPath());
    }

    private static String display(Path source, Path file) {
        Path root = source.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(root) && !absolute.equals(root)) {
            return root.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString();
    }

    private static String reason(Throwable error) {
        if (error instanceof NoSuchFileException) return "这个位置不存在，可能是失效的快捷方式或目录链接";
        if (error instanceof AccessDeniedException) return "没有读取权限，可能正被其他程序占用或属于系统文件夹";
        if (error instanceof FileSystemLoopException) return "目录链接形成了循环";
        if (error instanceof NotDirectoryException) return "它不是文件夹";
        if (error instanceof FileSystemException fs && fs.getReason() != null) return fs.getReason().trim();
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
