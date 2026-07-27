package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class ChangelogInput {
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private ChangelogInput() {
    }

    static String interactive(String input, Path baseDirectory) {
        String value = input == null ? "" : input.trim();
        if (!value.startsWith("@")) return value;
        String pathText = unquote(value.substring(1).trim());
        if (pathText.isEmpty()) {
            throw new ManagementException("@ 后面必须填写 UTF-8 文本文件路径");
        }
        try {
            Path path = Path.of(pathText);
            if (!path.isAbsolute()) path = baseDirectory.resolve(path);
            return utf8File(path);
        } catch (InvalidPathException e) {
            throw new ManagementException("更新记录文件路径无效", e);
        }
    }

    static String utf8File(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalized)) {
                throw new ManagementException("更新记录文件不存在或不是普通文件：" + normalized);
            }
            if (Files.size(normalized) > MAX_FILE_BYTES) {
                throw new ManagementException("更新记录文件不能超过 64 KiB：" + normalized);
            }
            String value = Files.readString(normalized, StandardCharsets.UTF_8);
            if (!value.isEmpty() && value.charAt(0) == '\ufeff') value = value.substring(1);
            return value.strip();
        } catch (IOException e) {
            throw new ManagementException("无法按 UTF-8 读取更新记录文件：" + normalized, e);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
