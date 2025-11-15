package com.zephyrstack.fxlib.core;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Blocking glass overlay with spinner and optional message.
 * Add/remove is idempotent, per-host. Safe to call from any thread.
 *
 * Usage:
 *   LoadingOverlay.show(rootPane, "Fetching data...");
 *   ... later ...
 *   LoadingOverlay.hide(rootPane);
 *
 * For try-with-resources:
 *   try (var handle = LoadingOverlay.block(rootPane, "Please wait")) {
 *       // do work
 *   }
 */
public final class LoadingOverlay implements AutoCloseable {
    private static final Map<Node, StackPane> OVERLAYS = new WeakHashMap<>();
    private final Node host;

    private LoadingOverlay(Node host) {
        this.host = host;
    }

    /** Show a blocking overlay with default message. */
    public static void show(Node host) { show(host, "Loading…"); }

    /** Show a blocking overlay over a Pane or its closest Pane ancestor. */
    public static void show(Node host, String message) {
        Objects.requireNonNull(host, "host");
        var ctx = ZephyrFxApplicationContext.getInstance();
        ctx.runOnFxThread(() -> {
            Pane pane = resolvePaneHost(host);
            if (pane == null) {
                throw new IllegalArgumentException("LoadingOverlay host must be inside a Pane (StackPane, AnchorPane, etc.).");
            }
            StackPane overlay = OVERLAYS.get(pane);
            if (overlay == null) {
                overlay = buildOverlay(message);
                bindTo(pane, overlay);
                pane.getChildren().add(overlay);
                overlay.toFront();
                OVERLAYS.put(pane, overlay);
            } else {
                setMessage(overlay, message);
                overlay.toFront();
                overlay.setVisible(true);
                overlay.setMouseTransparent(false);
            }
        });
    }

    /** Hide the overlay if present. */
    public static void hide(Node host) {
        Objects.requireNonNull(host, "host");
        var ctx = ZephyrFxApplicationContext.getInstance();
        ctx.runOnFxThread(() -> {
            Pane pane = resolvePaneHost(host);
            if (pane == null) return;
            StackPane overlay = OVERLAYS.get(pane);
            if (overlay != null) {
                overlay.setVisible(false);
                overlay.setMouseTransparent(true);
            }
        });
    }

    /** Remove overlay node entirely (optional; hide() is usually enough). */
    public static void detach(Node host) {
        Objects.requireNonNull(host, "host");
        var ctx = ZephyrFxApplicationContext.getInstance();
        ctx.runOnFxThread(() -> {
            Pane pane = resolvePaneHost(host);
            if (pane == null) return;
            StackPane overlay = OVERLAYS.remove(pane);
            if (overlay != null) {
                overlay.prefWidthProperty().unbind();
                overlay.prefHeightProperty().unbind();
                pane.getChildren().remove(overlay);
            }
        });
    }

    /** RAII-style blocking handle: closes on close(). */
    public static LoadingOverlay block(Node host, String message) {
        show(host, message);
        return new LoadingOverlay(host);
    }

    @Override public void close() { hide(host); }

    // ---------- helpers ----------
    private static StackPane buildOverlay(String message) {
        StackPane layer = new StackPane();
        layer.getStyleClass().add("zf-overlay");
        layer.setPickOnBounds(true); // block clicks
        layer.setMouseTransparent(false);
        layer.setVisible(true);

        StackPane card = new StackPane();
        card.getStyleClass().add("zf-overlay-card");
        card.setMaxWidth(320);
        card.setMinWidth(220);
        card.setMinHeight(96);
        card.setAlignment(Pos.CENTER);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        spinner.getStyleClass().add("zf-progress");

        Label label = new Label(message == null ? "Loading…" : message);
        label.getStyleClass().add("zf-message");

        StackPane content = new StackPane();
        content.setAlignment(Pos.CENTER);
        content.getChildren().addAll(spinner);

        StackPane wrapper = new StackPane();
        wrapper.setAlignment(Pos.CENTER);
        wrapper.getChildren().addAll(content);

        StackPane.setAlignment(label, Pos.BOTTOM_CENTER);
        label.setTranslateY(-16); // lift label slightly above bottom of card

        card.getChildren().addAll(wrapper, label);

        layer.getChildren().add(card);
        StackPane.setAlignment(card, Pos.CENTER);

        return layer;
    }

    private static void setMessage(StackPane overlay, String message) {
        overlay.lookupAll(".zf-message").stream()
                .filter(n -> n instanceof Label)
                .map(n -> (Label) n)
                .findFirst()
                .ifPresent(l -> l.setText(message == null ? "Loading…" : message));
    }

    private static void bindTo(Region host, Region overlay) {
        overlay.prefWidthProperty().bind(host.widthProperty());
        overlay.prefHeightProperty().bind(host.heightProperty());
    }

    private static Pane resolvePaneHost(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur instanceof Pane p) return p;
            cur = cur.getParent();
        }
        return null;
    }
}
