package cn.dreamingfish.updater.player;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.function.Consumer;

final class MarkdownNewsRenderer {
    private static final int MAX_MARKDOWN_IMAGE_WIDTH = 780;

    private final Parser parser = Parser.builder().build();
    private final Consumer<URI> openExternalLink;

    MarkdownNewsRenderer(Consumer<URI> openExternalLink) {
        this.openExternalLink = openExternalLink == null ? ignored -> { } : openExternalLink;
    }

    VBox render(String markdown) {
        VBox body = new VBox(14);
        body.getStyleClass().add("markdown-body");
        body.setFillWidth(true);
        Node document = parser.parse(markdown == null ? "" : markdown);
        appendBlocks(document.getFirstChild(), body);
        return body;
    }

    private void appendBlocks(Node first, VBox target) {
        for (Node node = first; node != null; node = node.getNext()) {
            javafx.scene.Node rendered = renderBlock(node);
            if (rendered != null) target.getChildren().add(rendered);
        }
    }

    private javafx.scene.Node renderBlock(Node node) {
        if (node instanceof Heading heading) return inlineFlow(heading, "markdown-heading",
                "markdown-h" + Math.min(heading.getLevel(), 3));
        if (node instanceof Paragraph paragraph) return renderParagraph(paragraph);
        if (node instanceof BlockQuote quote) return renderQuote(quote);
        if (node instanceof BulletList list) return renderList(list, false);
        if (node instanceof OrderedList list) return renderList(list, true);
        if (node instanceof FencedCodeBlock code) return codeBlock(code.getLiteral());
        if (node instanceof IndentedCodeBlock code) return codeBlock(code.getLiteral());
        if (node instanceof ThematicBreak) return divider();
        if (node instanceof HtmlBlock) return null;

        VBox nested = new VBox(10);
        appendBlocks(node.getFirstChild(), nested);
        return nested.getChildren().isEmpty() ? null : nested;
    }

    private javafx.scene.Node renderParagraph(Paragraph paragraph) {
        Node onlyChild = paragraph.getFirstChild();
        if (onlyChild instanceof org.commonmark.node.Image image && onlyChild.getNext() == null) {
            return renderImage(image);
        }
        return inlineFlow(paragraph, "markdown-paragraph");
    }

    private VBox renderQuote(BlockQuote quote) {
        VBox content = new VBox(9);
        content.getStyleClass().add("markdown-quote");
        appendBlocks(quote.getFirstChild(), content);
        return content;
    }

    private VBox renderList(ListBlock list, boolean ordered) {
        VBox rows = new VBox(8);
        rows.getStyleClass().add("markdown-list");
        int number = ordered && list instanceof OrderedList orderedList
                ? Math.max(1, orderedList.getMarkerStartNumber() == null
                        ? 1 : orderedList.getMarkerStartNumber()) : 1;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem item)) continue;
            Label marker = new Label(ordered ? number++ + "." : "•");
            marker.getStyleClass().add("markdown-list-marker");
            marker.setMinWidth(24);
            VBox itemBody = new VBox(6);
            itemBody.getStyleClass().add("markdown-list-item");
            appendBlocks(item.getFirstChild(), itemBody);
            HBox.setHgrow(itemBody, Priority.ALWAYS);
            HBox row = new HBox(8, marker, itemBody);
            row.setAlignment(Pos.TOP_LEFT);
            rows.getChildren().add(row);
        }
        return rows;
    }

    private Label codeBlock(String literal) {
        Label code = new Label(literal == null ? "" : literal.stripTrailing());
        code.getStyleClass().add("markdown-code-block");
        code.setWrapText(true);
        code.setMaxWidth(Double.MAX_VALUE);
        return code;
    }

    private Region divider() {
        Region divider = new Region();
        divider.getStyleClass().add("markdown-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        return divider;
    }

    private javafx.scene.Node renderImage(org.commonmark.node.Image imageNode) {
        String alternative = collectText(imageNode).strip();
        URL resource = NewsRepository.resolveImage(imageNode.getDestination());
        if (resource == null) return missingImage(alternative);

        javafx.scene.image.Image image = new javafx.scene.image.Image(
                resource.toExternalForm(), MAX_MARKDOWN_IMAGE_WIDTH, 0, true, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(MAX_MARKDOWN_IMAGE_WIDTH);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setAccessibleText(alternative.isBlank() ? "新闻图片" : alternative);

        Label fallback = missingImage(alternative);
        fallback.visibleProperty().bind(image.errorProperty());
        fallback.managedProperty().bind(image.errorProperty());
        view.visibleProperty().bind(image.errorProperty().not());
        view.managedProperty().bind(image.errorProperty().not());
        StackPane frame = new StackPane(fallback, view);
        frame.getStyleClass().add("markdown-image-frame");
        frame.setAlignment(Pos.CENTER_LEFT);
        frame.setMaxWidth(MAX_MARKDOWN_IMAGE_WIDTH);
        return frame;
    }

    private Label missingImage(String alternative) {
        Label missing = new Label(alternative.isBlank() ? "图片无法显示" : alternative);
        missing.getStyleClass().add("markdown-image-missing");
        missing.setWrapText(true);
        missing.setMaxWidth(Double.MAX_VALUE);
        return missing;
    }

    private TextFlow inlineFlow(Node parent, String... styleClasses) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().addAll(styleClasses);
        flow.setLineSpacing(5);
        flow.setMaxWidth(Double.MAX_VALUE);
        appendInlineChildren(parent, flow, false, false);
        return flow;
    }

    private void appendInlineChildren(Node parent, TextFlow flow, boolean strong, boolean emphasis) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text textNode) {
                Text text = styledText(textNode.getLiteral(), strong, emphasis);
                flow.getChildren().add(text);
            } else if (child instanceof Code codeNode) {
                Label code = new Label(codeNode.getLiteral());
                code.getStyleClass().add("markdown-inline-code");
                flow.getChildren().add(code);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                flow.getChildren().add(styledText("\n", strong, emphasis));
            } else if (child instanceof StrongEmphasis) {
                appendInlineChildren(child, flow, true, emphasis);
            } else if (child instanceof Emphasis) {
                appendInlineChildren(child, flow, strong, true);
            } else if (child instanceof Link link) {
                appendLink(link, flow);
            } else if (child instanceof org.commonmark.node.Image image) {
                String alternative = collectText(image);
                flow.getChildren().add(styledText(alternative.isBlank()
                        ? "[图片]" : "[图片：" + alternative + "]", strong, emphasis));
            } else if (!(child instanceof HtmlInline)) {
                appendInlineChildren(child, flow, strong, emphasis);
            }
        }
    }

    private void appendLink(Link link, TextFlow flow) {
        String text = collectText(link).strip();
        URI destination = safeWebUri(link.getDestination());
        if (destination == null) {
            flow.getChildren().add(styledText(text, false, false));
            return;
        }
        Hyperlink hyperlink = new Hyperlink(text.isBlank() ? destination.toString() : text);
        hyperlink.getStyleClass().add("markdown-link");
        hyperlink.setOnAction(event -> openExternalLink.accept(destination));
        hyperlink.setTooltip(new Tooltip(destination.toString()));
        flow.getChildren().add(hyperlink);
    }

    private static Text styledText(String value, boolean strong, boolean emphasis) {
        Text text = new Text(value == null ? "" : value);
        text.getStyleClass().add("markdown-text");
        if (strong) text.getStyleClass().add("markdown-strong");
        if (emphasis) text.getStyleClass().add("markdown-emphasis");
        return text;
    }

    static URI safeWebUri(String destination) {
        if (destination == null || destination.isBlank()) return null;
        try {
            URI uri = URI.create(destination.strip());
            if (!uri.isAbsolute()) return null;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("https") || scheme.equals("http") ? uri : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String collectText(Node parent) {
        StringBuilder value = new StringBuilder();
        collectText(parent.getFirstChild(), value);
        return value.toString();
    }

    private static void collectText(Node first, StringBuilder value) {
        for (Node node = first; node != null; node = node.getNext()) {
            if (node instanceof org.commonmark.node.Text text) value.append(text.getLiteral());
            else if (node instanceof Code code) value.append(code.getLiteral());
            else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) value.append(' ');
            else collectText(node.getFirstChild(), value);
        }
    }
}
