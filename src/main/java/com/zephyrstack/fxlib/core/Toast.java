package com.zephyrstack.fxlib.core;

import java.util.Objects;
import java.util.WeakHashMap;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public final class Toast {

    private static final WeakHashMap<StackPane, StackPane> HOSTS = new WeakHashMap<>();

    // NEW: global factory (can be replaced by app)
    private static volatile ToastViewFactory toastFactory = Toast::buildDefaultToast;

    private Toast() {}

    /**
     * App can call this once to override toast rendering globally.
     */
    public static void setToastFactory(ToastViewFactory factory) {
        toastFactory = Objects.requireNonNull(factory, "factory");
    }

    public static void show(Node anyNodeInScene, String message) {
        show(anyNodeInScene, message, Duration.millis(2200));
    }

    // NEW overload: use a one-off factory
    public static void show(Node anyNodeInScene, String message, Duration duration, ToastViewFactory customFactory) {
        Objects.requireNonNull(customFactory, "customFactory");
        internalShow(anyNodeInScene, message, duration, customFactory);
    }

    public static void show(Node anyNodeInScene, String message, Duration duration) {
        internalShow(anyNodeInScene, message, duration, toastFactory);
    }

    private static void internalShow(Node anyNodeInScene,
                                     String message,
                                     Duration duration,
                                     ToastViewFactory factory) {
        Objects.requireNonNull(anyNodeInScene, "node");
        Objects.requireNonNull(duration, "duration");
        ZephyrFxApplicationContext ctx = ZephyrFxApplicationContext.getInstance();
        ctx.runOnFxThread(() -> {
            StackPane root = findStackPane(anyNodeInScene);
            if (root == null) {
                throw new IllegalStateException("Toast host must be inside a StackPane (or wrap your root in one).");
            }
            HOSTS.putIfAbsent(root, root);

            Node toastNode;
            try {
                toastNode = factory.createToast(message);
            } catch (Exception e) {
                // fallback to default if custom fails
                toastNode = buildDefaultToast(message);
            }

            // we expect a StackPane-like node, but position any Node
            StackPane.setAlignment(toastNode, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(toastNode, new Insets(0, 24, 24, 24));

            root.getChildren().add(toastNode);
            toastNode.toFront();

            // same animation
            FadeTransition fadeIn = new FadeTransition(Duration.millis(140), toastNode);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);

            PauseTransition hold = new PauseTransition(duration);

            FadeTransition fadeOut = new FadeTransition(Duration.millis(180), toastNode);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            Node finalToastNode = toastNode;
            fadeOut.setOnFinished(e -> root.getChildren().remove(finalToastNode));

            new SequentialTransition(fadeIn, hold, fadeOut).play();
        });
    }

    // default builder (your current behavior)
    private static StackPane buildDefaultToast(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("zf-toast-label");

        StackPane toast = new StackPane(label);
        toast.getStyleClass().add("zf-toast");
        return toast;
    }

    private static StackPane findStackPane(Node n) {
        for (Node cur = n; cur != null; cur = cur.getParent()) {
            if (cur instanceof StackPane sp) {
                return sp;
            }
        }
        return null;
    }
}
