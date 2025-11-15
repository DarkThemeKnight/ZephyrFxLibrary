package com.zephyrstack.fxlib.core;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * Central place for lightweight dialogs, confirmations, and toast/overlay helpers.
 */
public final class DialogService {
    private final ZephyrFxApplicationContext ctx;

    public DialogService() {
        this(ZephyrFxApplicationContext.getInstance());
    }

    public DialogService(ZephyrFxApplicationContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    public Optional<ButtonType> info(String title, String message) {
        return showAlert(Alert.AlertType.INFORMATION, title, null, message);
    }

    public Optional<ButtonType> warn(String title, String message) {
        return showAlert(Alert.AlertType.WARNING, title, null, message);
    }

    public Optional<ButtonType> error(String title, String message) {
        return showAlert(Alert.AlertType.ERROR, title, null, message);
    }

    public boolean confirm(String title, String message) {
        Optional<ButtonType> result = showAlert(Alert.AlertType.CONFIRMATION, title, null, message);
        return result.map(ButtonType.OK::equals).orElse(false);
    }

    public void toast(Node nodeInScene, String message, Duration duration) {
        Toast.show(nodeInScene, message, duration);
    }

    public OverlayHandle showOverlay(Node nodeInScene, String message, Class<?> loadingOverlay) {
        try {
            Method show = loadingOverlay.getMethod("show", Node.class, String.class);
            show.invoke(null, nodeInScene, message);
            Method hide = loadingOverlay.getMethod("hide", Node.class);
            return (OverlayHandle) hide.invoke(null, nodeInScene);
        } catch (NoSuchMethodException e) {
            IO.println("No such method exception thrown");
            throw new RuntimeException(e);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }

    private Optional<ButtonType> showAlert(Alert.AlertType type,
                                           String title,
                                           String header,
                                           String message) {
        Stage owner = ctx.getPrimaryStage()
                .orElseThrow(() -> new IllegalStateException("Primary stage not set – call ctx.setPrimaryStage(stage)."));
        Alert alert = new Alert(type);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        decorateDialog(alert.getDialogPane());
        return alert.showAndWait();
    }

    private void decorateDialog(DialogPane pane) {
        Platform.runLater(() -> {
            ThemeManager.getInstance().applyActiveTheme(pane.getScene());
        });
    }

    public interface OverlayHandle extends AutoCloseable {
        @Override
        void close();
    }
}
