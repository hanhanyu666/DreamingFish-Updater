package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.PlayerPresentationImage;
import cn.dreamingfish.updater.protocol.PlayerServerCard;
import cn.dreamingfish.updater.protocol.ShowcaseRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One area of the player design that differs from the latest release, shown in the
 * publish preview next to the file changes.
 *
 * @param section the workbench design section: branding, home, server, colors, pages, images or music
 * @param title   the section name shown to the owner
 * @param items   the changed settings within the section
 * @param live    {@code true} when players already receive it from the saved settings;
 *                {@code false} when it reaches players only with the next release
 */
public record PresentationChange(String section, String title, List<String> items, boolean live) {
    public PresentationChange {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** One line for terminal output, for example “玩家端设计 · 服务器卡片：MOTD（已对玩家生效）”. */
    public String describe() {
        return "玩家端设计 · " + title + "：" + String.join("、", items)
                + (live ? "（已对玩家生效，发布时一并写入新版本）" : "（发布后玩家才会看到）");
    }

    /** Text and layout come from the live presentation; the background, music and images ship in releases. */
    public static List<PresentationChange> between(Branding released, Branding current) {
        if (released == null || current == null) return List.of();
        List<PresentationChange> result = new ArrayList<>();

        List<String> branding = new ArrayList<>();
        differs(branding, "欢迎文字", released.welcomeText(), current.welcomeText());
        differs(branding, "主标题", released.productName(), current.productName());
        differs(branding, "副标题", released.subtitle(), current.subtitle());
        differs(branding, "服务器地址", released.serverAddress(), current.serverAddress());
        differs(branding, "地址显示位置", placement(released), placement(current));
        differs(branding, "左上角图标", released.brandLogoFile(), current.brandLogoFile());
        differs(branding, "中文名称", released.brandName(), current.brandName());
        differs(branding, "英文名称", released.brandEnglishName(), current.brandEnglishName());
        add(result, "branding", "标题与品牌", branding, true);

        List<String> home = new ArrayList<>();
        differs(home, "焦点图", released.spotlights(), current.spotlights());
        differs(home, "活动", released.events(), current.events());
        add(result, "home", "首页焦点与活动", home, true);

        add(result, "server", "服务器卡片", serverCard(released.serverCard(), current.serverCard()), true);

        List<String> colors = new ArrayList<>();
        differs(colors, "强调色", released.accentColor(), current.accentColor());
        differs(colors, "辅助强调色", released.secondaryAccentColor(), current.secondaryAccentColor());
        differs(colors, "标题文字", released.titleColor(), current.titleColor());
        differs(colors, "顶部背景", released.topBarColor(), current.topBarColor());
        differs(colors, "顶部背景不透明度", released.topBarOpacity(), current.topBarOpacity());
        differs(colors, "内容卡片", released.cardColor(), current.cardColor());
        differs(colors, "卡片风格", released.theme(), current.theme());
        add(result, "colors", "外观与背景", colors, true);
        List<String> cover = new ArrayList<>();
        differs(cover, "背景图片", released.coverObject(), current.coverObject());
        add(result, "colors", "外观与背景", cover, false);

        List<String> pages = new ArrayList<>();
        differs(pages, "页面内容", released.contentPages(), current.contentPages());
        if (!same(released.newsArticles(), current.newsArticles())
                || !same(released.customPage(), current.customPage())) pages.add("旧版新闻与页面");
        add(result, "pages", "公告与页面", pages, true);

        List<String> images = new ArrayList<>();
        differs(images, "首页和页面引用的图片", released.presentationImages(), publishedImages(current));
        add(result, "images", "展示图片", images, false);

        List<String> music = new ArrayList<>();
        differs(music, "播放列表", released.musicTracks(), current.musicTracks());
        add(result, "music", "启动音乐", music, false);
        return List.copyOf(result);
    }

    /** Unset means the server card, so choosing it explicitly changes nothing for players. */
    private static String placement(Branding branding) {
        return branding.addressPlacement() == null ? "CARD" : branding.addressPlacement();
    }

    private static List<String> serverCard(PlayerServerCard released, PlayerServerCard current) {
        if (same(released, current)) return List.of();
        if (released == null) return List.of("添加服务器卡片");
        if (current == null) return List.of("移除服务器卡片");
        List<String> items = new ArrayList<>();
        differs(items, "显示开关", released.enabled(), current.enabled());
        differs(items, "在线人数和延迟", released.showStatus(), current.showStatus());
        differs(items, "版本文字", released.versionLabel(), current.versionLabel());
        differs(items, "MOTD", released.motd(), current.motd());
        differs(items, "Logo", released.logoFile(), current.logoFile());
        differs(items, "社区按钮", released.links(), current.links());
        return items.isEmpty() ? List.of("卡片设置") : items;
    }

    /** Releases keep only the uploaded images something still refers to; see PublishService. */
    private static List<PlayerPresentationImage> publishedImages(Branding branding) {
        if (branding.presentationImages() == null) return null;
        Set<String> referenced = ShowcaseRules.referencedImageFiles(branding);
        return branding.presentationImages().stream()
                .filter(image -> referenced.contains(image.fileName())).toList();
    }

    private static void add(List<PresentationChange> result, String section, String title,
                            List<String> items, boolean live) {
        if (!items.isEmpty()) result.add(new PresentationChange(section, title, items, live));
    }

    private static void differs(List<String> items, String name, Object released, Object current) {
        if (!same(released, current)) items.add(name);
    }

    /** A blank text or empty list means the same as an unset one to the player. */
    private static boolean same(Object released, Object current) {
        return Objects.equals(unset(released), unset(current));
    }

    private static Object unset(Object value) {
        if (value instanceof String text && text.isBlank()) return null;
        if (value instanceof Collection<?> list && list.isEmpty()) return null;
        return value;
    }
}
