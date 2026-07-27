package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.protocol.JsonCodec;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ManagementSettingsStore {
    private final Path file;
    private final JsonCodec json = new JsonCodec();

    ManagementSettingsStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    Path file() {
        return file;
    }

    ManagementSettings load() {
        if (!Files.isRegularFile(file)) return defaults();
        try {
            ManagementSettings settings = json.read(file, ManagementSettings.class);
            validate(settings);
            return normalize(settings);
        } catch (IOException e) {
            throw new ManagementException("无法读取管理端设置文件: " + file, e);
        }
    }

    void save(ManagementSettings settings) {
        validate(settings);
        ManagementSettings normalized = normalize(settings);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, json.write(normalized));
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new ManagementException("无法保存管理端设置文件: " + file, e);
        }
    }

    private ManagementSettings defaults() {
        Path parent = file.getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : file.getParent();
        return new ManagementSettings(ManagementSettings.CURRENT_SCHEMA,
                parent.resolve("data").toString(), "", "0.0.0.0", 8080);
    }

    private ManagementSettings normalize(ManagementSettings settings) {
        Path data = Path.of(settings.dataDirectory());
        if (!data.isAbsolute()) data = file.getParent().resolve(data);
        String projectId = settings.defaultProjectId() == null ? "" : settings.defaultProjectId().trim();
        return new ManagementSettings(ManagementSettings.CURRENT_SCHEMA,
                data.toAbsolutePath().normalize().toString(), projectId,
                settings.httpHost().trim(), settings.httpPort());
    }

    private static void validate(ManagementSettings settings) {
        if (settings == null || settings.schemaVersion() != ManagementSettings.CURRENT_SCHEMA) {
            throw new ManagementException("不支持的管理端设置文件版本");
        }
        if (settings.dataDirectory() == null || settings.dataDirectory().isBlank()) {
            throw new ManagementException("管理数据目录不能为空");
        }
        try {
            Path.of(settings.dataDirectory());
        } catch (RuntimeException e) {
            throw new ManagementException("管理数据目录格式无效", e);
        }
        String projectId = settings.defaultProjectId();
        if (projectId != null && !projectId.isBlank()
                && !projectId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("默认项目 ID 格式无效");
        }
        if (settings.httpHost() == null || settings.httpHost().isBlank()
                || settings.httpHost().length() > 255
                || settings.httpHost().chars().anyMatch(Character::isISOControl)) {
            throw new ManagementException("HTTP 监听地址无效");
        }
        if (settings.httpPort() < 1 || settings.httpPort() > 65535) {
            throw new ManagementException("HTTP 端口必须在 1 到 65535 之间");
        }
    }
}
