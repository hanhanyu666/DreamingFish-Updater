package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.PolicyChange;
import cn.dreamingfish.updater.management.PreviewWarning;
import cn.dreamingfish.updater.protocol.MaintenancePreset;

/** Chinese wording for maintenance presets and publish preview entries. */
final class PolicyText {
    private PolicyText() {
    }

    static String preset(String name) {
        if (name == null) return "（无）";
        return switch (name) {
            case "REQUIRED" -> "强制同步";
            case "SYNC" -> "普通同步";
            case "INITIAL" -> "首次提供";
            case "DEFAULT_CONFIG" -> "旧版：未修改才更新";
            case "LEGACY_MISSING_ONLY" -> "旧版缺失补齐";
            default -> name;
        };
    }

    static String preset(MaintenancePreset preset) {
        return preset == null ? "普通同步（默认）" : preset(preset.name());
    }

    static String describe(PolicyChange change) {
        return switch (change.kind()) {
            case "PRESET" -> "维护方式：" + change.subject() + "  "
                    + (change.previous() == null ? "新文件" : preset(change.previous()))
                    + " → " + preset(change.current());
            case "CLEANUP_DIRECTORY" -> "清理多余文件：" + change.subject() + "  "
                    + (change.current() == null ? "关闭" : "开启");
            case "OPTIONAL_GROUP" -> "可选分组：" + change.subject() + "  "
                    + (change.previous() == null ? "新增" : change.previous())
                    + " → " + (change.current() == null ? "删除" : change.current());
            case "OPTIONAL_MEMBERSHIP" -> "可选分组成员：" + change.subject() + "  "
                    + (change.previous() == null ? "必装" : change.previous())
                    + " → " + (change.current() == null ? "必装" : change.current());
            case "WITHDRAWAL" -> "撤回问题版本：" + change.subject() + "  "
                    + (change.current() == null ? "撤销" : "生效");
            case "CORRECTION" -> "修正配置：" + change.subject() + "  "
                    + (change.current() == null ? "撤销" : "生效");
            case "CORRECTION_DROPPED" -> "修正失效：" + change.subject()
                    + "（" + change.previous() + " 已不在发布内容中）";
            default -> change.kind() + " " + change.subject() + " " + change.previous()
                    + " -> " + change.current();
        };
    }

    static String describe(PreviewWarning warning) {
        String label = switch (warning.code()) {
            case PreviewWarning.PLAYER_PROGRAM_REQUIRED -> "需要新版玩家端";
            case PreviewWarning.CONTENT_MOD_REMOVED -> "可能影响单机存档";
            case PreviewWarning.STALE_RULE -> "规则已失效";
            default -> warning.code();
        };
        return "【" + label + "】" + warning.subject() + "：" + warning.message();
    }
}
