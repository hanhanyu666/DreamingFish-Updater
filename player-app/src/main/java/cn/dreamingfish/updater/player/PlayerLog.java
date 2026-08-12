package cn.dreamingfish.updater.player;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

final class PlayerLog {
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_ARCHIVES = 3;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    private final Path file;
    private final Clock clock;
    private final long maximumBytes;
    private volatile Consumer<String> listener = line -> { };

    PlayerLog(Path playerHome) {
        this(playerHome, Clock.systemDefaultZone(), MAX_BYTES);
    }

    PlayerLog(Path playerHome, Clock clock, long maximumBytes) {
        file = playerHome.resolve("logs/player-updater.log");
        this.clock = clock;
        this.maximumBytes = Math.max(1, maximumBytes);
        rotateIfRequired(0);
    }

    synchronized void startSession(String projectId, String playerVersion) {
        String project = sanitize(projectId);
        String version = sanitize(playerVersion);
        write("START", "启动", "玩家端 " + version + " · 项目 " + project, null);
    }

    synchronized void info(String message) {
        info("运行", message);
    }

    synchronized void info(String category, String message) {
        write("INFO", category, message, null);
    }

    synchronized void warn(String message) {
        warn("运行", message);
    }

    synchronized void warn(String category, String message) {
        write("WARN", category, message, null);
    }

    synchronized void error(String message, Throwable error) {
        error("运行", message, error);
    }

    synchronized void error(String category, String message, Throwable error) {
        write("ERROR", category, message, error);
    }

    void setListener(Consumer<String> listener) {
        this.listener = listener == null ? line -> { } : listener;
    }

    Path file() {
        return file;
    }

    List<String> readRecentLines(int maximum) {
        if (maximum <= 0 || !Files.isRegularFile(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return lines.size() <= maximum
                    ? List.copyOf(lines)
                    : List.copyOf(lines.subList(lines.size() - maximum, lines.size()));
        } catch (IOException e) {
            return List.of();
        }
    }

    private void write(String level, String category, String message, Throwable error) {
        String timestamp = TIMESTAMP.format(LocalDateTime.now(clock));
        String summary = summarize(error);
        String text = sanitize(message);
        if (!summary.isBlank() && !text.contains(summary)) {
            text += "：" + summary;
        }
        String line = timestamp + " | " + padLevel(level) + " | "
                + sanitizeCategory(category) + " | " + text;
        String persisted = line + System.lineSeparator();
        String trace = "";
        if (error != null) {
            trace = stackTrace(error);
            persisted += trace;
        }
        try {
            Files.createDirectories(file.getParent());
            rotateIfRequired(persisted.getBytes(StandardCharsets.UTF_8).length);
            Files.writeString(file, persisted, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
        listener.accept(line);
        if (!trace.isEmpty()) trace.lines().forEach(listener::accept);
    }

    private void rotateIfRequired(long incomingBytes) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)
                    && Files.size(file) + incomingBytes >= maximumBytes) {
                Files.deleteIfExists(file.resolveSibling(
                        file.getFileName() + "." + MAX_ARCHIVES));
                for (int i = MAX_ARCHIVES - 1; i >= 1; i--) {
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

    private static String padLevel(String level) {
        return String.format("%-5s", level);
    }

    private static String sanitizeCategory(String category) {
        String value = sanitize(category).replace('|', '/').trim();
        return value.isEmpty() ? "运行" : value;
    }

    private static String summarize(Throwable error) {
        if (error == null) return "";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return sanitize(message);
    }

    private static String stackTrace(Throwable error) {
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        StringBuilder result = new StringBuilder();
        output.toString().lines().forEach(line -> result.append("    ")
                .append(line)
                .append(System.lineSeparator()));
        return result.toString();
    }

    private static String sanitize(String message) {
        if (message == null) return "";
        return message.replace('\r', ' ').replace('\n', ' ').replace('|', '/').trim();
    }
}
