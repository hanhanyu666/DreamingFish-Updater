package cn.dreamingfish.updater.player;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.net.URL;
import java.util.Objects;

final class PlayerDialog {
    enum Tone {
        INFO("dfs-dialog-info"),
        WARNING("dfs-dialog-warning"),
        DANGER("dfs-dialog-danger");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private PlayerDialog() {
    }

    static boolean confirm(Stage owner, Tone tone, String title, String heading,
                           String message, String actionText, String cancelText) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(tone, "tone");

        ButtonType action = new ButtonType(actionText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(cancelText, ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setTitle(title);
        dialog.setResizable(false);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dfs-dialog-pane", tone.styleClass);
        URL stylesheet = PlayerDialog.class.getResource("player.css");
        if (stylesheet != null) pane.getStylesheets().add(stylesheet.toExternalForm());
        copyBrandingColors(owner, pane);
        configureOpaqueScene(pane);

        double width = preferredWidth(owner);
        double contentWidth = width - 76;
        pane.setMinWidth(width);
        pane.setPrefWidth(width);
        pane.setMaxWidth(width);
        pane.setMinHeight(Region.USE_PREF_SIZE);
        pane.setHeader(null);
        pane.setGraphic(null);
        pane.setContent(createContent(title, heading, message, contentWidth));
        pane.getButtonTypes().setAll(cancel, action);

        configureButton(pane, action, "dfs-dialog-primary", true, false);
        configureButton(pane, cancel, "dfs-dialog-secondary", false, true);
        dialog.setOnShowing(event -> prepareEntrance(pane));
        dialog.setOnShown(event -> playEntrance(pane));

        return dialog.showAndWait().orElse(cancel) == action;
    }

    private static VBox createContent(String title, String heading, String message,
                                      double contentWidth) {
        Region accent = new Region();
        accent.getStyleClass().add("dfs-dialog-accent");
        accent.setMinHeight(2);
        accent.setPrefHeight(2);
        accent.setMaxHeight(2);

        Label titleLabel = wrappingLabel(title, "dfs-dialog-title", contentWidth);
        Label headingLabel = wrappingLabel(heading, "dfs-dialog-heading", contentWidth);
        Label messageLabel = wrappingLabel(message, "dfs-dialog-message", contentWidth);
        VBox content = new VBox(9, accent, titleLabel, headingLabel, messageLabel);
        content.getStyleClass().add("dfs-dialog-content");
        content.setFillWidth(true);
        content.setMinWidth(contentWidth);
        content.setPrefWidth(contentWidth);
        content.setMaxWidth(contentWidth);
        VBox.setMargin(accent, new Insets(0, 0, 5, 0));
        VBox.setMargin(messageLabel, new Insets(3, 0, 0, 0));
        return content;
    }

    private static Label wrappingLabel(String text, String styleClass, double width) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setMinWidth(0);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static void configureButton(DialogPane pane, ButtonType type, String styleClass,
                                        boolean defaultButton, boolean cancelButton) {
        Button button = (Button) pane.lookupButton(type);
        button.getStyleClass().add(styleClass);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setDefaultButton(defaultButton);
        button.setCancelButton(cancelButton);
    }

    private static void copyBrandingColors(Stage owner, DialogPane pane) {
        Scene ownerScene = owner.getScene();
        if (ownerScene == null) return;
        Parent ownerRoot = ownerScene.getRoot();
        if (ownerRoot != null && ownerRoot.getStyle() != null) {
            pane.setStyle(ownerRoot.getStyle());
        }
    }

    private static void configureOpaqueScene(DialogPane pane) {
        pane.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) newScene.setFill(Color.web("#0b1114"));
        });
        if (pane.getScene() != null) pane.getScene().setFill(Color.web("#0b1114"));
    }

    private static double preferredWidth(Stage owner) {
        double ownerWidth = owner.getWidth();
        if (!Double.isFinite(ownerWidth) || ownerWidth <= 0) return 520;
        return Math.max(480, Math.min(560, ownerWidth * 0.48));
    }

    private static void prepareEntrance(DialogPane pane) {
        pane.setOpacity(0);
    }

    private static void playEntrance(DialogPane pane) {
        FadeTransition fade = new FadeTransition(Duration.millis(150), pane);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
    }
}
