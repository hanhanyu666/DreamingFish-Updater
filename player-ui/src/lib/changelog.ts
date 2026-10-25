/** What the owner wrote about a release, split into lines the card and the details page can list. */

export interface ChangelogLine {
  /** 新增、更新、修复、调整、移除 or 删除 when the line starts with one, otherwise empty. */
  tag: string;
  /** accent, blue or orange for a tagged line, empty for plain text. */
  tone: string;
  text: string;
}

export const EMPTY_CHANGELOG = "本次发布没有填写更新说明。";

const CHANGE_TAGS: Record<string, string> = {
  新增: "accent",
  更新: "blue",
  修复: "blue",
  调整: "blue",
  移除: "orange",
  删除: "orange",
};

/** The changelog with surrounding whitespace removed, or the empty-changelog sentence. */
export function changelogText(changelog: string | null | undefined): string {
  const text = changelog?.trim() ?? "";
  return text.length === 0 ? EMPTY_CHANGELOG : text;
}

const TAGGED = /^(?:[【[](新增|更新|修复|调整|移除|删除)[】\]][：:\s]*|(新增|更新|修复|调整|移除|删除)[：:\s]\s*)(.+)$/;

/**
 * Lines that start with 新增、更新、修复、调整、移除 or 删除 get that word as a tag: in 【】 or [],
 * or followed by a colon or a space. List markers are dropped and other lines stay plain.
 */
export function changelogLines(changelog: string | null | undefined): ChangelogLine[] {
  return changelogText(changelog)
    .split(/\r?\n/)
    .map((line) => line.trim().replace(/^[-*•·]\s*/, ""))
    .filter((line) => line.length > 0)
    .map((line) => {
      const match = TAGGED.exec(line);
      const tag = match?.[1] ?? match?.[2];
      return match == null || tag == null
        ? { tag: "", tone: "", text: line }
        : { tag, tone: CHANGE_TAGS[tag], text: match[3] };
    });
}
