package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Private, reversible project settings; signing keys and account data are excluded. */
public record ProjectSettingsSnapshot(String displayName, String sourceDirectory,
                                      String publicBaseUrl, Branding branding, ProjectRules rules) {
    public static ProjectSettingsSnapshot of(ProjectRecord project) {
        return new ProjectSettingsSnapshot(project.displayName(), project.sourceDirectory().toString(),
                project.publicBaseUrl(), project.branding(), project.rules());
    }

    public List<Change> changesTo(ProjectSettingsSnapshot next) {
        List<Change> result = new ArrayList<>();
        add(result, "项目名称", displayName, next.displayName);
        add(result, "整合包目录", sourceDirectory, next.sourceDirectory);
        add(result, "玩家下载地址", publicBaseUrl, next.publicBaseUrl);
        if (!Objects.equals(branding, next.branding)) {
            add(result,"玩家端标题",branding.productName(),next.branding.productName());
            add(result,"玩家端副标题",branding.subtitle(),next.branding.subtitle());
            add(result,"中文品牌名",branding.brandName(),next.branding.brandName());
            add(result,"英文品牌名",branding.brandEnglishName(),next.branding.brandEnglishName());
            add(result,"游戏服务器地址",branding.serverAddress(),next.branding.serverAddress());
            add(result,"封面",branding.coverObject(),next.branding.coverObject());
            add(result,"强调色",branding.accentColor(),next.branding.accentColor());
            add(result,"次强调色",branding.secondaryAccentColor(),next.branding.secondaryAccentColor());
            add(result,"标题颜色",branding.titleColor(),next.branding.titleColor());
            add(result,"欢迎文字",branding.welcomeText(),next.branding.welcomeText());
            add(result,"顶栏颜色",branding.topBarColor(),next.branding.topBarColor());
            add(result,"顶栏不透明度",branding.topBarOpacity(),next.branding.topBarOpacity());
            add(result,"卡片颜色",branding.cardColor(),next.branding.cardColor());
            if(!Objects.equals(branding.contentPages(),next.branding.contentPages()))result.add(new Change("页面与公告","原页面内容","修改后的页面内容"));
            if(!Objects.equals(branding.newsArticles(),next.branding.newsArticles())||!Objects.equals(branding.customPage(),next.branding.customPage()))result.add(new Change("旧版页面与新闻","原内容","修改后的内容"));
            if(!Objects.equals(branding.musicTracks(),next.branding.musicTracks()))result.add(new Change("启动音乐","原播放列表","修改后的播放列表"));
        }
        Map<String, String> before = ruleValues(rules), after = ruleValues(next.rules);
        java.util.Set<String> names = new java.util.LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) add(result, name, before.getOrDefault(name, "未设置"),
                after.getOrDefault(name, "未设置"));
        return List.copyOf(result);
    }

    /** Reverts affected settings while retaining unrelated later project edits. */
    public static ProjectSettingsSnapshot revert(ProjectSettingsSnapshot current,
                                                   ProjectSettingsSnapshot before,
                                                   ProjectSettingsSnapshot after) {
        String name = revertField("项目名称", current.displayName, before.displayName, after.displayName);
        String source = revertField("整合包目录", current.sourceDirectory, before.sourceDirectory, after.sourceDirectory);
        String url = revertField("玩家下载地址", current.publicBaseUrl, before.publicBaseUrl, after.publicBaseUrl);
        Branding brand = revertField("玩家端个性化", current.branding, before.branding, after.branding);
        if (!before.rules.equals(after.rules) && !current.sourceDirectory.equals(after.sourceDirectory)) {
            throw new ManagementException("整合包目录已改变，不能直接撤销原目录的维护设置");
        }
        ProjectRules target = revertField("维护设置", current.rules, before.rules, after.rules);
        if (current.rules.simplified() && !target.simplified()) target = target.asSimplified();
        new RuleSet(target);
        return new ProjectSettingsSnapshot(name, Path.of(source).toAbsolutePath().normalize().toString(), url, brand, target);
    }

    private static <T> T revertField(String name, T current, T before, T after) {
        if (Objects.equals(before, after)) return current;
        if (!Objects.equals(current, after)) {
            throw new ManagementException(name + "后来又有修改，请先查看或撤销后续操作");
        }
        return before;
    }

    private static void add(List<Change> changes, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.add(new Change(name, String.valueOf(before), String.valueOf(after)));
    }

    private static Map<String, String> ruleValues(ProjectRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        for (PresetRule rule : rules.presets()) {
            values.put(rule.path() + (rule.directory() ? "/" : "") + " · 维护方式", switch (rule.preset()) {
                case REQUIRED -> "强制同步";
                case SYNC -> "普通同步";
                case INITIAL -> "首次提供";
                case DEFAULT_CONFIG -> "旧版默认配置";
            });
        }
        for (String path : rules.cleanupDirectories()) values.put(path + "/ · 额外文件", "备份移出");
        for (OptionalGroupRule group : rules.optionalGroups()) {
            values.put("可选内容 · " + group.id(), group.title() + (group.defaultInstall() ? "（默认安装）" : "（默认不安装）")
                    + " · " + String.join("、", group.files()) + " " + String.join("、", group.directories()) + " " + String.join("、", group.modIds())
                    + (group.description().isBlank() ? "" : " · " + group.description()));
        }
        for (var rule : rules.withdrawals()) values.put("问题或移除要求 · " + rule.id(), rule.reason() + " · " + rule.items().stream().map(item -> item.path()).toList());
        for (var rule : rules.corrections()) values.put("文件修复 · " + rule.id(), rule.path() + " · " + rule.reason() + " · " + rule.mode());
        if (!rules.rules().isEmpty()) values.put("文件管理范围", rules.rules().toString());
        return values;
    }

    public record Change(String setting, String before, String after) {}
}
