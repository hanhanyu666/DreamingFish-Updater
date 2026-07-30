package cn.dreamingfish.updater.player;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class NewsPage {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter
            .ofPattern("yyyy.MM.dd", Locale.ROOT);
    private static final double CARD_COVER_WIDTH = 236;
    private static final double CARD_COVER_HEIGHT = 133;

    private final StackPane root = new StackPane();
    private final List<NewsArticle> articles;
    private final Node listView;
    private Consumer<URI> openExternalLink = ignored -> { };
    private Runnable interactionAction = () -> { };
    private boolean showingArticle;

    NewsPage() {
        List<NewsArticle> loaded;
        String loadError = null;
        try {
            loaded = NewsRepository.loadBundled();
        } catch (RuntimeException error) {
            loaded = List.of();
            loadError = error.getMessage();
        }
        articles = loaded;
        listView = createListView(loadError);
        root.getChildren().add(listView);
    }

    Node root() {
        return root;
    }

    void setOpenExternalLink(Consumer<URI> action) {
        openExternalLink = action == null ? ignored -> { } : action;
    }

    void setInteractionAction(Runnable action) {
        interactionAction = action == null ? () -> { } : action;
    }

    boolean showingArticle() {
        return showingArticle;
    }

    void showList() {
        showingArticle = false;
        replaceContent(listView);
    }

    List<NewsArticle> articles() {
        return articles;
    }

    NewsArticle latestArticle() {
        return articles.isEmpty() ? null : articles.getFirst();
    }

    void showLatestArticle() {
        NewsArticle latest = latestArticle();
        if (latest != null) showArticle(latest);
    }

    private Node createListView(String loadError) {
        VBox page = createPageBody(900);
        page.getChildren().addAll(
                label("DREAMINGFISH NEWS", "page-eyebrow"),
                label("梦鱼服新闻", "page-title"),
                label("服务器动态、开发进度与世界观档案。", "page-lead"),
                divider()
        );

        VBox cards = new VBox(16);
        cards.getStyleClass().add("news-card-list");
        if (articles.isEmpty()) {
            String message = loadError == null || loadError.isBlank()
                    ? "还没有发布新闻"
                    : "新闻暂时无法载入，不影响游戏更新与启动";
            cards.getChildren().add(label(message, "news-empty"));
        } else {
            for (NewsArticle article : articles) cards.getChildren().add(createCard(article));
        }
        VBox.setMargin(cards, new Insets(18, 0, 0, 0));
        page.getChildren().add(cards);
        return pageScroll(page);
    }

    private Node createCard(NewsArticle article) {
        StackPane cover = cover(article.cover(), CARD_COVER_WIDTH, CARD_COVER_HEIGHT,
                "news-card-cover", article.title());
        cover.setMinWidth(CARD_COVER_WIDTH);

        Label date = label("NEWS  ·  " + DISPLAY_DATE.format(article.publishedOn()),
                "news-card-date");
        Label title = label(article.title(), "news-card-title");
        Label summary = label(article.summary(), "news-card-summary");
        summary.setMaxHeight(48);
        Button read = new Button("阅读全文  ›");
        read.getStyleClass().add("news-read-button");
        read.setOnAction(event -> showArticle(article));

        VBox text = new VBox(7, date, title, summary, read);
        text.setAlignment(Pos.TOP_LEFT);
        text.setFillWidth(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        VBox.setVgrow(summary, Priority.ALWAYS);

        HBox card = new HBox(16, cover, text);
        card.getStyleClass().add("news-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setCursor(Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleText(article.title() + "，" + article.summary());
        card.setOnMouseClicked(event -> {
            if (!event.isStillSincePress() || isInside(event.getTarget(), read)) return;
            showArticle(article);
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                showArticle(article);
                event.consume();
            }
        });
        return card;
    }

    private void showArticle(NewsArticle article) {
        interactionAction.run();
        showingArticle = true;

        Button back = new Button("‹  返回新闻");
        back.getStyleClass().add("news-back-button");
        back.setOnAction(event -> {
            interactionAction.run();
            showList();
        });

        VBox page = createPageBody(900);
        StackPane hero = cover(article.cover(), 900, 330, "news-article-cover", article.title());
        Label date = label("DREAMINGFISH NEWS  ·  "
                + DISPLAY_DATE.format(article.publishedOn()), "page-eyebrow");
        Label title = label(article.title(), "page-title");
        Label summary = label(article.summary(), "page-lead");
        VBox markdown = new MarkdownNewsRenderer(uri -> openExternalLink.accept(uri))
                .render(article.markdown());

        VBox.setMargin(hero, new Insets(17, 0, 13, 0));
        VBox.setMargin(markdown, new Insets(16, 0, 0, 0));
        page.getChildren().addAll(back, hero, date, title, summary, divider(), markdown);
        replaceContent(pageScroll(page));
    }

    private void replaceContent(Node content) {
        root.getChildren().setAll(content);
        if (content instanceof ScrollPane scroll) scroll.setVvalue(0);
    }

    private static StackPane cover(java.net.URL resource, double width, double height,
                                   String styleClass, String alternative) {
        Image image = new Image(resource.toExternalForm(), 0, 0, true, true, false);
        Region picture = new Region();
        picture.getStyleClass().add("news-cover-image");
        picture.setMinSize(0, height);
        picture.setPrefSize(width, height);
        picture.setMaxSize(Double.MAX_VALUE, height);
        picture.setBackground(new Background(new BackgroundImage(
                image, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO,
                        false, false, false, true))));
        picture.setAccessibleText(alternative);

        Label missing = label("图片无法显示", "news-cover-missing");
        missing.visibleProperty().bind(image.errorProperty());
        missing.managedProperty().bind(image.errorProperty());
        StackPane frame = new StackPane(picture, missing);
        frame.getStyleClass().add(styleClass);
        frame.setMinSize(0, height);
        frame.setPrefSize(width, height);
        frame.setMaxSize(width, height);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        frame.setClip(clip);
        return frame;
    }

    private static boolean isInside(Object target, Node ancestor) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static VBox createPageBody(double maximumWidth) {
        VBox page = new VBox();
        page.getStyleClass().add("content-page");
        page.setMaxWidth(maximumWidth);
        return page;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Region divider() {
        Region divider = new Region();
        divider.getStyleClass().add("page-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        VBox.setMargin(divider, new Insets(24, 0, 5, 0));
        return divider;
    }

    private static ScrollPane pageScroll(VBox page) {
        StackPane alignment = new StackPane(page);
        alignment.getStyleClass().add("content-page-alignment");
        alignment.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(page, Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(alignment);
        scroll.getStyleClass().add("content-page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(true);
        return scroll;
    }
}
