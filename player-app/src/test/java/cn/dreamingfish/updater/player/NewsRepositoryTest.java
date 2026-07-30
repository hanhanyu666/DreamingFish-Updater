package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;
import org.commonmark.node.Node;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsRepositoryTest {
    @Test
    void loadsBundledArticlesAndTheirMarkdown() {
        var articles = NewsRepository.loadBundled();

        assertFalse(articles.isEmpty());
        assertEquals("来自另一维度的求助", articles.getFirst().title());
        assertTrue(articles.getFirst().markdown().contains("建筑先行服现已开启"));
        assertTrue(articles.getFirst().cover().toExternalForm().contains("hero-dreamhaven.png"));
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
        String markdown = NewsRepository.loadBundled().getFirst().markdown();
        Node document = Parser.builder().build().parse(markdown);

        assertTrue(containsNode(document, StrongEmphasis.class));
        assertFalse(containsText(document, "**"));
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
