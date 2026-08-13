package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLogTest {
    @TempDir
    Path temporary;

    @Test
    void writesDatedSessionCategoriesAndUsefulErrorDetails() throws Exception {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-13T02:03:04.567Z"),
                ZoneId.of("Asia/Shanghai"));
        PlayerLog log = new PlayerLog(temporary, clock, 64 * 1024);
        List<String> visible = new ArrayList<>();
        log.setListener(visible::add);

        log.startSession("building_server", "0.1.38");
        log.info("检查更新", "正在检查整合包更新");
        log.error("整合包更新", "更新失败", new IOException("连接超时"));

        assertTrue(visible.size() > 3);
        assertEquals("2026-08-13 10:03:04.567 | START | 启动 | 玩家端 0.1.38 · 项目 building_server",
                visible.getFirst());
        assertTrue(visible.get(1).contains("| INFO  | 检查更新 | 正在检查整合包更新"));
        assertTrue(visible.get(2).contains("| ERROR | 整合包更新 | 更新失败：连接超时"));
        assertTrue(visible.stream().anyMatch(line ->
                line.contains("java.io.IOException: 连接超时")));

        String persisted = Files.readString(log.file(), StandardCharsets.UTF_8);
        assertTrue(persisted.contains("java.io.IOException: 连接超时"));
        assertTrue(persisted.contains("PlayerLogTest"));
    }

    @Test
    void rotatesWhileRunningAndKeepsOnlyThreeArchives() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneId.of("UTC"));
        PlayerLog log = new PlayerLog(temporary, clock, 180);

        for (int i = 0; i < 30; i++) {
            log.info("下载文件", "正在下载第 " + i + " 个测试文件 abcdefghijklmnopqrstuvwxyz.jar");
        }

        Path file = log.file();
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.isRegularFile(file.resolveSibling("player-updater.log.1")));
        assertTrue(Files.isRegularFile(file.resolveSibling("player-updater.log.3")));
        assertFalse(Files.exists(file.resolveSibling("player-updater.log.4")));
        assertTrue(log.readRecentLines(2).size() <= 2);
    }
}
