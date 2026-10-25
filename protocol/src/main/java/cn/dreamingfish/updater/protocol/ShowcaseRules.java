package cn.dreamingfish.updater.protocol;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Server-side validation of the fixed showcase format. Errors identify the JSON field. */
public final class ShowcaseRules {
    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_IMAGES = 100;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.(png|jpe?g|webp|gif)");
    private static final Set<String> BLOCKS = Set.of("TEXT", "CARDS", "STEPS", "LIST", "TEAM", "FAQ", "STATS", "CALLOUT", "IMAGE", "GALLERY");
    private static final Set<String> ICONS = Set.of("star", "shield", "users", "chat", "globe", "calendar", "pin", "image", "sparkle", "sword", "pickaxe", "heart", "home", "gift", "trophy", "book", "clock", "check", "info", "signal");

    private ShowcaseRules() { }

    public static void validate(Branding branding) {
        List<PlayerContentPage> pages = list(branding.contentPages());
        validateArticleMetadata(branding.newsArticles(), "newsArticles");
        if (branding.spotlights() != null || branding.serverCard() != null || branding.events() != null
                || branding.theme() != null || pages.stream().anyMatch(page -> page.layout() != null)) {
            text(branding.productName(), "productName", 60, true);
            text(branding.subtitle(), "subtitle", 300, false);
            text(branding.welcomeText(), "welcomeText", 24, false);
            text(branding.brandName(), "brandName", 24, false);
            text(branding.brandEnglishName(), "brandEnglishName", 24, false);
        }
        for (int p = 0; p < pages.size(); p++) {
            PlayerContentPage page = pages.get(p);
            String path = "contentPages[" + p + "]";
            validateArticleMetadata(page.articles(), path + ".articles");
            image(page.imageFile(), page.imageUrl(), path);
            count(page.tags(), path + ".tags", 6);
            for (int tag = 0; tag < list(page.tags()).size(); tag++) {
                text(page.tags().get(tag), path + ".tags[" + tag + "]", 24, true);
            }
            option(page.layout(), path + ".layout", Set.of("MARKDOWN", "ANNOUNCEMENTS", "GALLERY", "BLOCKS"));
            // Old pages keep their existing limits; newly typed pages use the showcase schema.
            if (page.layout() != null) {
                text(page.navigationLabel(), path + ".navigationLabel", 24, true);
                text(page.eyebrow(), path + ".eyebrow", 40, false);
                text(page.title(), path + ".title", 60, true);
                text(page.lead(), path + ".lead", 300, false);
                text(page.markdown(), path + ".markdown", 20_000, false);
                validateArticles(page.articles(), path + ".articles");
            }
            count(page.gallery(), path + ".gallery", 60);
            Set<String> galleryIds = new HashSet<>();
            int g = 0;
            for (PlayerGalleryItem image : list(page.gallery())) {
                String at = path + ".gallery[" + g++ + "]";
                unique(image.id(), at + ".id", galleryIds);
                text(image.title(), at + ".title", 60, false);
                text(image.author(), at + ".author", 40, false);
                text(image.category(), at + ".category", 24, false);
                text(image.caption(), at + ".caption", 300, false);
                text(image.location(), at + ".location", 40, false);
                image(image.imageFile(), image.imageUrl(), at);
                if (blank(image.imageFile()) && blank(image.imageUrl())) fail(at, "需要提供图片文件或公开图片地址");
            }
            count(page.blocks(), path + ".blocks", 20);
            Set<String> blockIds = new HashSet<>();
            int b = 0;
            for (PlayerBlock block : list(page.blocks())) {
                String at = path + ".blocks[" + b++ + "]";
                unique(block.id(), at + ".id", blockIds);
                requiredOption(block.kind(), at + ".kind", BLOCKS);
                option(block.tone(), at + ".tone", Set.of("DEFAULT", "ACCENT", "SECONDARY", "WARM", "MUTED"));
                if (block.columns() != null && (block.columns() < 1 || block.columns() > 4)) fail(at + ".columns", "必须是 1–4 之间的整数");
                text(block.title(), at + ".title", 60, false);
                text(block.lead(), at + ".lead", 300, false);
                text(block.navLabel(), at + ".navLabel", 24, false);
                text(block.eyebrow(), at + ".eyebrow", 40, false);
                text(block.markdown(), at + ".markdown", 20_000, false);
                image(block.imageFile(), block.imageUrl(), at);
                count(block.items(), at + ".items", 24);
                Set<String> itemIds = new HashSet<>();
                int i = 0;
                for (PlayerBlockItem item : list(block.items())) {
                    String itemAt = at + ".items[" + i++ + "]";
                    unique(item.id(), itemAt + ".id", itemIds);
                    text(item.title(), itemAt + ".title", 60, false);
                    text(item.text(), itemAt + ".text", 600, false);
                    text(item.meta(), itemAt + ".meta", 40, false);
                    option(item.icon(), itemAt + ".icon", ICONS);
                    image(item.imageFile(), item.imageUrl(), itemAt);
                    action(item.action(), itemAt + ".action", pages);
                }
                count(block.actions(), at + ".actions", 3);
                int a = 0;
                for (PlayerLinkTarget action : list(block.actions())) action(action, at + ".actions[" + a++ + "]", pages);
            }
        }
        // Spotlights and events have no count limit; only each entry is checked.
        Set<String> ids = new HashSet<>();
        int s = 0;
        for (PlayerSpotlight spotlight : list(branding.spotlights())) {
            String at = "spotlights[" + s++ + "]";
            unique(spotlight.id(), at + ".id", ids);
            text(spotlight.tag(), at + ".tag", 24, false);
            text(spotlight.label(), at + ".label", 24, false);
            text(spotlight.eyebrow(), at + ".eyebrow", 40, false);
            text(spotlight.title(), at + ".title", 60, true);
            text(spotlight.lead(), at + ".lead", 300, false);
            image(spotlight.imageFile(), spotlight.imageUrl(), at);
            action(spotlight.action(), at + ".action", pages);
        }
        PlayerServerCard card = branding.serverCard();
        if (card != null) {
            text(card.versionLabel(), "serverCard.versionLabel", 24, false);
            text(card.motd(), "serverCard.motd", 200, false);
            imageFile(card.logoFile(), "serverCard.logoFile");
            count(card.links(), "serverCard.links", 4);
            ids.clear();
            int l = 0;
            for (PlayerCommunityLink link : list(card.links())) {
                String at = "serverCard.links[" + l++ + "]";
                unique(link.id(), at + ".id", ids);
                requiredOption(link.kind(), at + ".kind", Set.of("QQ", "DISCORD", "KOOK", "BILIBILI", "WEBSITE", "OTHER"));
                text(link.label(), at + ".label", 24, false);
                text(link.copyText(), at + ".copyText", 60, false);
                url(link.url(), at + ".url");
                if (blank(link.url()) && blank(link.copyText())) fail(at, "需要提供链接地址或复制内容");
            }
        }
        ids.clear();
        int e = 0;
        for (PlayerEvent event : list(branding.events())) {
            String at = "events[" + e++ + "]";
            unique(event.id(), at + ".id", ids);
            text(event.title(), at + ".title", 60, true);
            OffsetDateTime starts = time(event.startsAt(), at + ".startsAt");
            if (event.endsAt() != null && !time(event.endsAt(), at + ".endsAt").isAfter(starts)) fail(at + ".endsAt", "必须晚于开始时间");
            action(event.action(), at + ".action", pages);
        }
        option(branding.addressPlacement(), "addressPlacement", Branding.ADDRESS_PLACEMENTS);
        imageFile(branding.brandLogoFile(), "brandLogoFile");
        if (branding.theme() != null) {
            option(branding.theme().corner(), "theme.corner", Set.of("ROUND", "SOFT", "SQUARE"));
            option(branding.theme().surface(), "theme.surface", Set.of("GLASS", "SOLID", "CLEAR"));
        }
        count(branding.presentationImages(), "presentationImages", MAX_IMAGES);
        ids.clear();
        for (PlayerPresentationImage image : list(branding.presentationImages())) {
            validateImageFileName(image.fileName());
            if (!ids.add(image.fileName().toLowerCase(Locale.ROOT))) fail("presentationImages", "图片文件名不能重复");
            if (!Hex.isSha256(image.sha256()) || image.size() < 1 || image.size() > MAX_IMAGE_BYTES) fail("presentationImages", "图片哈希或大小无效，单张不能超过 8 MiB");
        }
    }

    public static String validateImageFileName(String name) {
        if (name == null || name.length() > 120 || !IMAGE.matcher(name).matches()) fail("imageFile", "必须是 120 字符以内的 PNG、JPEG、WebP 或 GIF 安全文件名");
        try { PathSafety.normalizeManifestPath(name); }
        catch (ProtocolException error) { fail("imageFile", "图片文件名不安全"); }
        return name;
    }

    /** References are names only; content identity always comes from the signed release. */
    public static Set<String> referencedImageFiles(Branding branding) {
        Set<String> result = new LinkedHashSet<>();
        for (PlayerSpotlight slide : list(branding.spotlights())) add(result, slide.imageFile());
        add(result, branding.brandLogoFile());
        if (branding.serverCard() != null) add(result, branding.serverCard().logoFile());
        for (PlayerContentPage page : list(branding.contentPages())) {
            add(result, page.imageFile());
            for (PlayerGalleryItem image : list(page.gallery())) add(result, image.imageFile());
            for (PlayerBlock block : list(page.blocks())) {
                add(result, block.imageFile());
                for (PlayerBlockItem item : list(block.items())) add(result, item.imageFile());
            }
        }
        return result;
    }

    private static void validateArticleMetadata(List<PlayerNewsArticle> articles, String path) {
        int index = 0;
        for (PlayerNewsArticle article : list(articles)) {
            String at = path + "[" + index++ + "]";
            text(article.category(), at + ".category", 24, false);
            text(article.author(), at + ".author", 40, false);
        }
    }

    private static void validateArticles(List<PlayerNewsArticle> articles, String path) {
        count(articles, path, 100);
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (PlayerNewsArticle article : list(articles)) {
            String at = path + "[" + index++ + "]";
            unique(article.id(), at + ".id", ids);
            text(article.title(), at + ".title", 60, true);
            text(article.summary(), at + ".summary", 300, false);
            text(article.markdown(), at + ".markdown", 20_000, false);
            url(article.coverUrl(), at + ".coverUrl");
            try { LocalDate.parse(article.publishedOn()); }
            catch (RuntimeException error) { fail(at + ".publishedOn", "必须是有效的 YYYY-MM-DD 日期"); }
        }
    }

    private static void action(PlayerLinkTarget action, String path, List<PlayerContentPage> pages) {
        if (action == null) return;
        text(action.label(), path + ".label", 24, false);
        requiredOption(action.kind(), path + ".kind", Set.of("PAGE", "ARTICLE", "URL"));
        if (action.pageId() != null) id(action.pageId(), path + ".pageId");
        if (action.articleId() != null) id(action.articleId(), path + ".articleId");
        url(action.url(), path + ".url");
        if (action.kind().equals("URL")) {
            if (blank(action.url())) fail(path + ".url", "请填写 http(s) 链接");
            return;
        }
        PlayerContentPage page = pages.stream().filter(p -> p.id().equals(action.pageId())).findFirst().orElse(null);
        if (page == null) fail(path + ".pageId", "指向的页面不存在");
        if (action.kind().equals("ARTICLE") && (page.layout() == null ? !page.announcementPage() : !page.layout().equals("ANNOUNCEMENTS"))) fail(path + ".pageId", "指向的页面不是公告页");
        if (action.kind().equals("ARTICLE") && list(page.articles()).stream().noneMatch(a -> a.id().equals(action.articleId()))) fail(path + ".articleId", "指向的公告不存在");
    }

    private static void image(String file, String url, String path) {
        imageFile(file, path + ".imageFile");
        url(url, path + ".imageUrl");
    }

    private static void imageFile(String file, String path) {
        if (file == null) return;
        try { validateImageFileName(file); }
        catch (ProtocolException error) { throw new ProtocolException(path + " 图片文件名无效：" + file, error); }
    }

    private static void url(String value, String path) {
        if (blank(value)) return;
        text(value, path, 2048, false);
        try {
            URI uri = new URI(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) fail(path, "仅支持不含账户信息的 http(s) 公开链接");
        } catch (java.net.URISyntaxException error) { fail(path, "链接地址无效"); }
    }

    private static OffsetDateTime time(String value, String path) {
        try { return OffsetDateTime.parse(value); }
        catch (RuntimeException error) { throw new ProtocolException(path + " 必须是带时区的 ISO 8601 时间"); }
    }

    private static void unique(String value, String path, Set<String> ids) {
        id(value, path);
        if (!ids.add(value)) fail(path, "标识不能重复");
    }

    private static void id(String value, String path) {
        if (value == null || !ID.matcher(value).matches()) fail(path, "必须是 1–64 位小写字母、数字或连字符，且以字母或数字开头");
    }

    private static void text(String value, String path, int maximum, boolean required) {
        if (required && blank(value)) fail(path, "不能为空");
        if (value != null && value.codePointCount(0, value.length()) > maximum) fail(path, "不能超过 " + maximum + " 字");
    }

    private static void option(String value, String path, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) fail(path, "不支持 \"" + value + "\"");
    }

    private static void requiredOption(String value, String path, Set<String> allowed) {
        if (value == null) fail(path, "不能为空");
        option(value, path, allowed);
    }

    private static void count(List<?> items, String path, int maximum) {
        if (items != null && items.size() > maximum) fail(path, "最多允许 " + maximum + " 项");
    }

    private static void add(Set<String> files, String value) { if (!blank(value)) files.add(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static <T> List<T> list(List<T> value) { return value == null ? List.of() : value; }
    private static void fail(String path, String message) { throw new ProtocolException(path + " " + message); }
}
