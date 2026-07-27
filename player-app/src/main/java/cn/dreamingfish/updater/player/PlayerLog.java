package cn.dreamingfish.updater.player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class PlayerLog {
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Path file;
    private volatile Consumer<String> listener = line -> { };

    PlayerLog(Path playerHome) {
        file = playerHome.resolve("logs/player-updater.log");
        rotate();
    }

    synchronized void info(String message) {
        write("INFO", message, null);
    }

    synchronized void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    void setListener(Consumer<String> listener) {
        this.listener = listener == null ? line -> { } : listener;
    }

    Path file() {
        return file;
    }

    private void write(String level, String message, Throwable error) {
        String line = TIME.format(Instant.now()) + "  " + level + "  " + sanitize(message);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (error != null) {
                Files.writeString(file, "    " + error + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
        }
        listener.accept(line);
    }

    private void rotate() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file) && Files.size(file) >= MAX_BYTES) {
                for (int i = 2; i >= 1; i--) {
                    Path source = file.resolveSibling(file.getFileName() + "." + i);
                    Path target = file.resolveSibling(file.getFileName() + "." + (i + 1));
                    if (Files.exists(source)) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String message) {
        if (message == null) return "";
        return message.replace('\r', ' ').replace('\n', ' ');
    }
}
