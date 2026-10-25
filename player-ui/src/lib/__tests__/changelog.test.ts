import { describe, expect, it } from "vitest";
import { changelogLines, changelogText, EMPTY_CHANGELOG } from "../changelog";

describe("changelog lines", () => {
  it("tags lines that start with a change word and keeps the rest plain", () => {
    expect(changelogLines("新增：永夜维度\n- 【修复】交易所上架\n• 调整 光影可选\n感谢大家的反馈")).toEqual([
      { tag: "新增", tone: "accent", text: "永夜维度" },
      { tag: "修复", tone: "blue", text: "交易所上架" },
      { tag: "调整", tone: "blue", text: "光影可选" },
      { tag: "", tone: "", text: "感谢大家的反馈" },
    ]);
    expect(changelogLines("移除: 旧模组\r\n\r\n删除：旧配置").map((line) => line.tone)).toEqual(["orange", "orange"]);
  });

  it("falls back to the empty-changelog sentence", () => {
    expect(changelogText("  ")).toBe(EMPTY_CHANGELOG);
    expect(changelogText(null)).toBe(EMPTY_CHANGELOG);
    expect(changelogLines(undefined)).toEqual([{ tag: "", tone: "", text: EMPTY_CHANGELOG }]);
  });
});
