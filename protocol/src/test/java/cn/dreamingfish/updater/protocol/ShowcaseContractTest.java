package cn.dreamingfish.updater.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ShowcaseContractTest {
    private final JsonCodec json = new JsonCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode example() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of("..", "docs", "showcase", "example-whalesong.json")));
    }

    private Branding branding(ObjectNode node) throws Exception { return json.read(mapper.writeValueAsBytes(node), Branding.class); }

    @Test void completeOwnerExampleValidatesAndRoundTripsEveryNewComponent() throws Exception {
        ObjectNode document = example();
        Branding branding = branding(document);
        assertDoesNotThrow(() -> ManifestValidator.validatePlayerPresentation(new PlayerPresentation(1, "demo", branding)));
        assertEquals(branding, json.read(json.write(branding), Branding.class));
        assertEquals("ANNOUNCEMENTS", branding.contentPages().getFirst().layout());
        assertFalse(branding.spotlights().isEmpty());
        assertNotNull(branding.serverCard());
        assertNotNull(branding.theme());
        assertFalse(ShowcaseRules.referencedImageFiles(branding).isEmpty());
        assertEquals(document.at("/spotlights/0/label").asText(), branding.spotlights().getFirst().label());
        PlayerNewsArticle article = branding.contentPages().getFirst().articles().getFirst();
        assertEquals(document.at("/contentPages/0/articles/0/category").asText(), article.category());
        assertEquals(document.at("/contentPages/0/articles/0/author").asText(), article.author());
        PlayerContentPage guide = branding.contentPages().get(1);
        assertEquals("guide-cover.webp", guide.imageFile());
        assertEquals(document.at("/contentPages/1/tags").size(), guide.tags().size());
        assertEquals(document.at("/contentPages/1/tags/0").asText(), guide.tags().getFirst());
        assertEquals(document.at("/contentPages/1/blocks/0/eyebrow").asText(), guide.blocks().getFirst().eyebrow());
        assertEquals(Boolean.TRUE, branding.contentPages().get(2).gallery().getFirst().featured());
        assertTrue(ShowcaseRules.referencedImageFiles(branding).contains("guide-cover.webp"));
    }

    @Test void presentationPreservesTextAndReferencesButStripsFileIdentities() throws Exception {
        var image = new PlayerPresentationImage("cover.png", "a".repeat(64), 42);
        Branding source = branding(example()).withPresentationImages(List.of(image)).withCoverObject("b".repeat(64))
                .withMusicTracks(List.of(new PlayerMusicTrack("music", "音乐", "theme.mp3", "c".repeat(64), 42)));
        Branding presentation = new PlayerPresentation(1, "demo", source).branding();
        assertNull(presentation.coverObject());
        assertNull(presentation.musicTracks());
        assertNull(presentation.presentationImages());
        assertEquals(source.spotlights(), presentation.spotlights());
        assertEquals(source.contentPages(), presentation.contentPages());
        assertEquals(source.serverCard(), presentation.serverCard());
        assertEquals(source.events(), presentation.events());
        assertEquals(source.theme(), presentation.theme());
        assertEquals(List.of(image), source.presentationImages());
    }

    @Test void rejectsBrokenReferencesAndUnsafeWebAddressesWithFieldPaths() throws Exception {
        rejected(node -> ((ObjectNode) node.at("/spotlights/0/action")).put("pageId", "missing-page"), "spotlights[0].action.pageId");
        rejected(node -> ((ObjectNode) node.at("/spotlights/0")).set("action", mapper.createObjectNode()
                .put("kind", "ARTICLE").put("pageId", "news").put("articleId", "missing")), "articleId");
        rejected(node -> ((ObjectNode) node.at("/spotlights/0")).set("action", mapper.createObjectNode()
                .put("kind", "URL").put("url", "javascript:alert(1)")), "action.url");
        rejected(node -> ((ObjectNode) node.at("/spotlights/0")).put("imageUrl", "file:///C:/secret.png"), "imageUrl");
    }

    @Test void rejectsOversizeCollectionsTextAndInvalidEnums() throws Exception {
        rejected(node -> ((ObjectNode) node.at("/spotlights/0")).put("title", "鱼".repeat(61)), "title");
        rejected(node -> node.put("addressPlacement", "FOOTER"), "addressPlacement");
        rejected(node -> node.put("brandLogoFile", "../logo.png"), "brandLogoFile");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("kind", "HERO"), "contentPages[1].blocks[0].kind");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("columns", 5), "columns");
        rejected(node -> ((ObjectNode) node.get("theme")).put("corner", "FREEFORM"), "theme.corner");
    }

    @Test void validatesTimeZonesAndChronologicalEventRange() throws Exception {
        rejected(node -> ((ObjectNode) node.at("/events/0")).put("startsAt", "2026-10-24T20:00:00"), "startsAt");
        rejected(node -> ((ObjectNode) node.at("/events/0")).put("endsAt", "2000-01-01T00:00:00Z"), "endsAt");
    }

    @Test void rejectsNewMetadataOverItsLimitsAndReportsTheFullFieldPath() throws Exception {
        rejected(node -> ((ObjectNode) node.at("/spotlights/0")).put("label", "鱼".repeat(25)), "spotlights[0].label");
        rejected(node -> ((ObjectNode) node.at("/contentPages/0/articles/0")).put("category", "鱼".repeat(25)), "contentPages[0].articles[0].category");
        rejected(node -> ((ObjectNode) node.at("/contentPages/0/articles/0")).put("author", "鱼".repeat(41)), "contentPages[0].articles[0].author");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("eyebrow", "鱼".repeat(41)), "contentPages[1].blocks[0].eyebrow");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).putArray("tags").add("鱼".repeat(25)), "contentPages[1].tags[0]");
        rejected(node -> { var tags = ((ObjectNode) node.at("/contentPages/1")).putArray("tags"); for (int i = 0; i < 7; i++) tags.add("标签"); }, "contentPages[1].tags");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).putArray("tags").add(" "), "contentPages[1].tags[0]");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).putArray("tags").addNull(), "contentPages[1].tags[0]");
    }

    @Test void validatesPageCoversWithTheSameRulesAsOtherImages() throws Exception {
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageFile", "../outside.png"), "contentPages[1].imageFile");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageFile", "cover.svg"), "contentPages[1].imageFile");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageUrl", "javascript:alert(1)"), "contentPages[1].imageUrl");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageUrl", "file:///C:/secret.png"), "contentPages[1].imageUrl");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageUrl", "https://user:password@example.com/image.png"), "contentPages[1].imageUrl");
        rejected(node -> ((ObjectNode) node.at("/contentPages/1")).put("imageUrl", "https://example.com/" + "a".repeat(2048)), "contentPages[1].imageUrl");
    }

    @Test void validatesNewsMetadataOnLegacyArticlesAndPagesToo() throws Exception {
        rejected(node -> {
            var articles = node.at("/contentPages/0/articles").deepCopy();
            ((ObjectNode) articles.get(0)).put("category", "鱼".repeat(25));
            node.set("newsArticles", articles);
        }, "newsArticles[0].category");
        rejected(node -> {
            ((ObjectNode) node.at("/contentPages/0")).remove("layout");
            ((ObjectNode) node.at("/contentPages/0/articles/0")).put("author", "鱼".repeat(41));
        }, "contentPages[0].articles[0].author");
        rejected(node -> {
            ((ObjectNode) node.at("/contentPages/1")).remove("layout");
            ((ObjectNode) node.at("/contentPages/1")).putArray("tags").add("");
        }, "contentPages[1].tags[0]");
    }

    @Test void acceptsUnicodeAtTheLimitsAndRetainsFalseFeaturedFlags() throws Exception {
        ObjectNode node = example();
        String label = "🐟".repeat(24), eyebrow = "🐟".repeat(40);
        ((ObjectNode) node.at("/spotlights/0")).put("label", label);
        ((ObjectNode) node.at("/contentPages/0/articles/0")).put("category", label).put("author", eyebrow);
        ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("eyebrow", eyebrow);
        ((ObjectNode) node.at("/contentPages/1")).put("imageFile", "a".repeat(116) + ".png");
        var tags = ((ObjectNode) node.at("/contentPages/1")).putArray("tags");
        for (int i = 0; i < 6; i++) tags.add(label);
        ((ObjectNode) node.at("/contentPages/2/gallery/0")).put("featured", false);
        Branding source = branding(node);
        assertDoesNotThrow(() -> ManifestValidator.validatePlayerPresentation(new PlayerPresentation(1, "demo", source)));
        Branding restored = json.read(json.write(source), Branding.class);
        assertEquals(label, restored.spotlights().getFirst().label());
        assertEquals(eyebrow, restored.contentPages().getFirst().articles().getFirst().author());
        assertEquals(6, restored.contentPages().get(1).tags().size());
        assertEquals(Boolean.FALSE, restored.contentPages().get(2).gallery().getFirst().featured());
    }

    @Test void oldConstructorsAndOldJsonKeepNewFieldsAbsent() {
        assertNull(new PlayerSpotlight("slide", "", "", "标题", "", null, null, null).label());
        PlayerNewsArticle article = new PlayerNewsArticle("news", "公告", "", "2026-10-23", "", "");
        assertNull(article.category());
        assertNull(article.author());
        assertNull(new PlayerGalleryItem("image", "图片", "", "", null, null, null, "https://example.com/image.png").featured());
        assertNull(new PlayerBlock("block", "TEXT", null, null, null, null, null, "", null, null, null, null).eyebrow());
        for (var page : List.of(new PlayerContentPage("page", "页面", false, "", "标题", "", "", List.of()),
                new PlayerContentPage("page", "页面", false, "", "标题", "", "", List.of(), "MARKDOWN", null, null))) {
            assertNull(page.imageFile());
            assertNull(page.imageUrl());
            assertNull(page.tags());
        }
        Branding old = json.read("""
                {"productName":"旧项目","spotlights":[{"id":"slide","title":"旧标题"}],
                "contentPages":[{"id":"page","title":"旧页面","articles":[{"id":"news","title":"旧公告"}]}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8), Branding.class);
        assertNull(old.spotlights().getFirst().label());
        assertNull(old.contentPages().getFirst().tags());
        assertNull(old.contentPages().getFirst().articles().getFirst().category());
    }

    @Test void rejectsFractionalAndStringColumnCountsInsteadOfSilentlyTruncating() throws Exception {
        ObjectNode node = example();
        ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("columns", 2.5);
        assertThrows(ProtocolException.class, () -> branding(node));
        ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("columns", "2");
        assertThrows(ProtocolException.class, () -> branding(node));
        ((ObjectNode) node.at("/contentPages/1/blocks/0")).put("columns", 2.0);
        assertEquals(2, branding(node).contentPages().get(1).blocks().getFirst().columns());
    }

    @Test void rejectsUnsafeImageNamesAndSignedMetadataLimits() {
        for (String name : List.of("../cover.png", "folder/cover.png", "C:\\cover.png", "CON.png", "cover.svg", "cover.png.exe")) {
            assertThrows(ProtocolException.class, () -> ShowcaseRules.validateImageFileName(name), name);
        }
        assertThrows(ProtocolException.class, () -> ShowcaseRules.validate(Branding.empty().withPresentationImages(
                List.of(new PlayerPresentationImage("safe.png", "a".repeat(64), ShowcaseRules.MAX_IMAGE_BYTES + 1)))));
        assertThrows(ProtocolException.class, () -> ShowcaseRules.validate(Branding.empty().withPresentationImages(
                List.of(new PlayerPresentationImage("safe.png", "a".repeat(64), 1), new PlayerPresentationImage("SAFE.png", "b".repeat(64), 1)))));
    }

    @Test void acceptsAnyNumberOfSpotlightsAndEvents() throws Exception {
        ObjectNode node = example();
        var slides = (com.fasterxml.jackson.databind.node.ArrayNode) node.get("spotlights");
        var events = (com.fasterxml.jackson.databind.node.ArrayNode) node.get("events");
        ObjectNode slide = (ObjectNode) slides.get(0);
        ObjectNode event = (ObjectNode) events.get(0);
        for (int i = 0; i < 30; i++) {
            slides.add(slide.deepCopy().put("id", "many-slide-" + i));
            events.add(event.deepCopy().put("id", "many-event-" + i));
        }
        Branding branding = branding(node);
        ManifestValidator.validatePlayerPresentation(new PlayerPresentation(1, "demo", branding));
        assertEquals(slides.size(), branding.spotlights().size());
        assertEquals(events.size(), branding.events().size());
    }

    private void rejected(Consumer<ObjectNode> mutate, String path) throws Exception {
        ObjectNode node = example();
        mutate.accept(node);
        Branding branding = branding(node);
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> ManifestValidator.validatePlayerPresentation(new PlayerPresentation(1, "demo", branding)));
        assertTrue(error.getMessage().contains(path), error.getMessage());
    }
}
