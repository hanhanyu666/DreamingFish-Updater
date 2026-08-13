import { describe, expect, it } from "vitest";
import { groupPlayerLogs, logLevelLabel, parsePlayerLogs } from "../playerLogs";

describe("player log formatting", () => {
  it("parses dated entries and attaches stack trace lines to their error", () => {
    const entries = parsePlayerLogs([
      "2026-08-13 10:03:04.567 | START | 启动 | 玩家端 0.1.38 · 项目 building_server",
      "2026-08-13 10:03:05.120 | INFO  | 检查更新 | 正在检查整合包更新",
      "2026-08-13 10:03:06.900 | ERROR | 整合包更新 | 更新失败：连接超时",
      "    java.io.IOException: 连接超时",
      "    at cn.dreamingfish.Test.run(Test.java:1)",
    ]);

    expect(entries).toHaveLength(3);
    expect(entries[0]).toMatchObject({ date: "2026-08-13", level: "START", category: "启动" });
    expect(entries[2].details).toEqual([
      "java.io.IOException: 连接超时",
      "at cn.dreamingfish.Test.run(Test.java:1)",
    ]);
  });

  it("keeps old undated logs readable in a clearly marked group", () => {
    const groups = groupPlayerLogs([
      "12:08:41  INFO  Checking for updates",
      "12:08:42  ERROR  Update failed",
      "    java.io.IOException: timeout",
    ], new Date(2026, 7, 13, 12));

    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe("旧版记录 · 当时未保存日期");
    expect(groups[0].entries[1].details).toEqual(["java.io.IOException: timeout"]);
  });

  it("labels today and presents levels in Chinese", () => {
    const groups = groupPlayerLogs([
      "2026-08-13 10:03:05.120 | WARN  | 网络 | 连接较慢",
    ], new Date(2026, 7, 13, 12));

    expect(groups[0].label).toBe("今天 · 2026年8月13日");
    expect(logLevelLabel("WARN")).toBe("提醒");
    expect(logLevelLabel("ERROR")).toBe("错误");
  });
});
