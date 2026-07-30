package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;
import org.commonmark.node.Node;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsRepositoryTest {
    @Test
    void loadsBundledArticlesAndTheirMarkdown() {
        var articles = NewsRepository.loadBundled();

        assertEquals(2, articles.size());
        NewsArticle introduction = articles.getFirst();
        assertEquals("welcome-to-dreamhaven", introduction.id());
        assertEquals("初识梦屿：灯还亮着", introduction.title());
        assertTrue(introduction.markdown().contains("梦屿"));
        assertTrue(introduction.markdown().contains("外缘带"));
        assertTrue(introduction.markdown().contains("逐光会"));
        assertTrue(introduction.markdown().contains("梁朔"));
        assertTrue(introduction.markdown().length() < 1_500, "世界观导览应保持简短");

        NewsArticle announcement = articles.get(1);
        assertEquals("来自另一维度的求助", announcement.title());
        assertTrue(announcement.markdown().contains("建筑先行服现已开启"));
        assertTrue(announcement.cover().toExternalForm().contains("hero-dreamhaven.png"));
    }

    @Test
    void acceptsOnlyWebLinksAndSafeImageResources() {
        assertEquals(URI.create("https://dreamingfish.top"),
                MarkdownNewsRenderer.safeWebUri("https://dreamingfish.top"));
        assertNull(MarkdownNewsRenderer.safeWebUri("file:///C:/Windows/win.ini"));
        assertNull(MarkdownNewsRenderer.safeWebUri("javascript:alert(1)"));
        assertNull(NewsRepository.resolveImage("../project-binding.json"));
    }

    @Test
    void bundledMarkdownParsesStrongTextWithoutLeakingMarkers() {
        boolean foundStrongText = false;
        for (NewsArticle article : NewsRepository.loadBundled()) {
            Node document = Parser.builder().build().parse(article.markdown());
            foundStrongText |= containsNode(document, StrongEmphasis.class);
            assertFalse(containsText(document, "**"),
                    () -> article.id() + " 泄漏了 Markdown 粗体标记");
        }
        assertTrue(foundStrongText);
    }

    @Test
    void bundledNewsDoesNotRevealLateGameTerms() {
        String corpus = NewsRepository.loadBundled().stream()
                .map(article -> article.title() + "\n" + article.summary() + "\n" + article.markdown())
                .reduce("", (left, right) -> left + "\n" + right);

        for (String spoiler : List.of(
                "注定邪恶", "幕后反派", "真正的反派", "必然推翻",
                "唯一结局", "最终结局", "全部真相", "后来变质",
                "强硬派", "稳定感染者", "永久拒绝")) {
            assertFalse(corpus.contains(spoiler), () -> "新闻提前泄露后期设定：" + spoiler);
        }
    }

    private static boolean containsNode(Node node, Class<? extends Node> type) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (type.isInstance(current) || containsNode(current.getFirstChild(), type)) return true;
        }
        return false;
    }

    private static boolean containsText(Node node, String fragment) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Text text && text.getLiteral().contains(fragment)) return true;
            if (containsText(current.getFirstChild(), fragment)) return true;
        }
        return false;
    }
}
