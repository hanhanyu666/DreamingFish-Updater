package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.PlayerCommunityLink;
import cn.dreamingfish.updater.protocol.PlayerMusicTrack;
import cn.dreamingfish.updater.protocol.PlayerPresentationImage;
import cn.dreamingfish.updater.protocol.PlayerServerCard;
import cn.dreamingfish.updater.protocol.PlayerSpotlight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationChangeTest {
    @TempDir
    Path temporary;

    private static final Branding BASE = Branding.empty();

    private static Branding with(String productName, String subtitle, String cover, String accent,
                                 List<PlayerMusicTrack> music, List<PlayerSpotlight> spotlights,
                                 PlayerServerCard card, List<PlayerPresentationImage> images) {
        return new Branding(productName, subtitle, BASE.serverAddress(), cover, accent,
                BASE.secondaryAccentColor(), BASE.brandName(), BASE.brandEnglishName(), null, null, null,
                music, BASE.welcomeText(), BASE.topBarColor(), BASE.cardColor(), BASE.topBarOpacity(),
                BASE.titleColor(), spotlights, card, null, null, images);
    }

    private static Branding base() {
        return with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, null, null, null);
    }

    @Test
    void sameDesignAndBlankValuesReportNothing() {
        assertEquals(List.of(), PresentationChange.between(base(), base()));
        Branding blank = with(BASE.productName(), " ", null, BASE.accentColor(), List.of(), List.of(), null, List.of());
        Branding unset = with(BASE.productName(), null, null, BASE.accentColor(), null, null, null, null);
        assertEquals(List.of(), PresentationChange.between(unset, blank));
        assertEquals(List.of(), PresentationChange.between(null, base()));
    }

    @Test
    void separatesLiveSettingsFromOnesThatShipWithReleases() {
        Branding current = with("鲸歌生存服", BASE.subtitle(), "cover-sha", "#ff8800",
                List.of(new PlayerMusicTrack("sea", "海边", "sea.mp3", "a".repeat(64), 10)), null,
                new PlayerServerCard(true, true, null, "§b鲸歌", null, List.of()), null);

        List<PresentationChange> changes = PresentationChange.between(base(), current);

        assertEquals(List.of(
                new PresentationChange("branding", "标题与品牌", List.of("主标题"), true),
                new PresentationChange("server", "服务器卡片", List.of("添加服务器卡片"), true),
                new PresentationChange("colors", "外观与背景", List.of("强调色"), true),
                new PresentationChange("colors", "外观与背景", List.of("背景图片"), false),
                new PresentationChange("music", "启动音乐", List.of("播放列表"), false)), changes);
        assertEquals("玩家端设计 · 服务器卡片：添加服务器卡片（已对玩家生效，发布时一并写入新版本）",
                changes.get(1).describe());
    }

    @Test
    void choosingTheDefaultAddressPlaceChangesNothing() {
        Branding card = base();
        Branding explicit = withPlacement(card, "CARD");
        assertEquals(List.of(), PresentationChange.between(card, explicit));
        assertEquals(List.of(new PresentationChange("branding", "标题与品牌", List.of("地址显示位置"), true)),
                PresentationChange.between(explicit, withPlacement(card, "HERO")));
    }

    private static Branding withPlacement(Branding b, String placement) {
        return new Branding(b.productName(), b.subtitle(), b.serverAddress(), b.coverObject(), b.accentColor(),
                b.secondaryAccentColor(), b.brandName(), b.brandEnglishName(), b.newsArticles(), b.customPage(),
                b.contentPages(), b.musicTracks(), b.welcomeText(), b.topBarColor(), b.cardColor(), b.topBarOpacity(),
                b.titleColor(), b.spotlights(), b.serverCard(), b.events(), b.theme(), b.presentationImages(), placement,
                b.brandLogoFile());
    }

    @Test
    void theTopLeftLogoIsLiveButItsImageShipsWithTheRelease() {
        PlayerPresentationImage logo = new PlayerPresentationImage("logo.png", "d".repeat(64), 10);
        Branding uploaded = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, null, null,
                List.of(logo));
        Branding b = uploaded;
        Branding chosen = new Branding(b.productName(), b.subtitle(), b.serverAddress(), b.coverObject(),
                b.accentColor(), b.secondaryAccentColor(), b.brandName(), b.brandEnglishName(), b.newsArticles(),
                b.customPage(), b.contentPages(), b.musicTracks(), b.welcomeText(), b.topBarColor(), b.cardColor(),
                b.topBarOpacity(), b.titleColor(), b.spotlights(), b.serverCard(), b.events(), b.theme(),
                b.presentationImages(), b.addressPlacement(), "logo.png");

        assertEquals(List.of(
                new PresentationChange("branding", "标题与品牌", List.of("左上角图标"), true),
                new PresentationChange("images", "展示图片", List.of("首页和页面引用的图片"), false)),
                PresentationChange.between(base(), chosen));
    }

    @Test
    void namesTheServerCardSettingsThatChanged() {
        PlayerServerCard card = new PlayerServerCard(true, true, null, "§b鲸歌", null, List.of());
        PlayerServerCard edited = new PlayerServerCard(true, false, null, "§b鲸歌 §7第三赛季", null,
                List.of(new PlayerCommunityLink("qq", "QQ", "", null, "123456")));
        Branding released = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, null, card, null);
        Branding current = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, null, edited, null);

        assertEquals(List.of(new PresentationChange("server", "服务器卡片",
                List.of("在线人数和延迟", "MOTD", "社区按钮"), true)), PresentationChange.between(released, current));
    }

    @Test
    void comparesOnlyTheImagesAReleaseWouldCarry() {
        PlayerPresentationImage hero = new PlayerPresentationImage("hero.webp", "b".repeat(64), 20);
        PlayerPresentationImage spare = new PlayerPresentationImage("spare.webp", "c".repeat(64), 30);
        Branding uploaded = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, null, null,
                List.of(hero, spare));
        assertEquals(List.of(), PresentationChange.between(base(), uploaded), "unused uploads are not published");

        List<PlayerSpotlight> slides = List.of(new PlayerSpotlight("s", "", "", "新赛季", "", "hero.webp", null, null));
        Branding used = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, slides, null,
                List.of(hero, spare));
        assertEquals(List.of(
                new PresentationChange("home", "首页焦点与活动", List.of("焦点图"), true),
                new PresentationChange("images", "展示图片", List.of("首页和页面引用的图片"), false)),
                PresentationChange.between(base(), used));
        Branding released = with(BASE.productName(), BASE.subtitle(), null, BASE.accentColor(), null, slides, null,
                List.of(hero));
        assertEquals(List.of(), PresentationChange.between(released, used));
    }

    @Test
    void scanListsDesignChangesUntilTheyArePublished() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        Files.writeString(fixture.source.resolve("options.txt"), "music:1");
        fixture.scanner.createPreview(project.id());
        fixture.publisher.publish(project.id(), "1.0.0", "0.1.0", "First");
        assertEquals(List.of(), fixture.scanner.createPreview(project.id()).presentationChanges());

        Branding old = project.branding();
        Branding edited = new Branding(old.productName(), old.subtitle(), "play.example.com", old.coverObject(),
                old.accentColor(), old.secondaryAccentColor(), old.brandName(), old.brandEnglishName(),
                old.newsArticles(), old.customPage(), old.contentPages(), old.musicTracks(), old.welcomeText(),
                old.topBarColor(), old.cardColor(), old.topBarOpacity(), old.titleColor(), old.spotlights(),
                new PlayerServerCard(true, true, null, null, null, List.of()), old.events(), old.theme(),
                old.presentationImages());
        fixture.projects.configure(project.id(), null, null, edited, null);

        PublishPreview preview = fixture.scanner.createPreview(project.id());
        assertTrue(preview.changes().isEmpty(), "no modpack file changed");
        assertEquals(List.of(
                new PresentationChange("branding", "标题与品牌", List.of("服务器地址"), true),
                new PresentationChange("server", "服务器卡片", List.of("添加服务器卡片"), true)),
                preview.presentationChanges());

        fixture.publisher.publish(project.id(), "1.0.1", "0.1.0", "Server card");
        assertEquals(List.of(), fixture.scanner.createPreview(project.id()).presentationChanges());
    }
}
