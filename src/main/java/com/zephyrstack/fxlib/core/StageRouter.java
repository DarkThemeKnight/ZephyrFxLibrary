package com.zephyrstack.fxlib.core;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Pair;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Simple, opinionated navigation helper built on ZephyrFxApplicationContext.
 * - Reuses Scene where possible (preserves size, keeps global styles).
 * - One-liners for primary stage navigation and modal dialogs.
 */
public final class StageRouter {
    private final ZephyrFxApplicationContext ctx;
    private Stage primary;
    private Object activeController;

    public StageRouter() {
        this(ZephyrFxApplicationContext.getInstance());
    }

    public StageRouter(ZephyrFxApplicationContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.primary = ctx.getPrimaryStage()
                .orElseThrow(() -> new IllegalStateException("Primary stage not set. Call ctx.setPrimaryStage(stage) first."));
    }

    public void setPrimary(Stage primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        ctx.setPrimaryStage(primary);
    }

    /**
     * Show an FXML as the root of the primary stage and return its controller.
     */
    public <C> C show(String fxmlPath, String title) {
        return show(primary, fxmlPath, title, null, null);
    }

    /**
     * Overload to apply extra configuration to the stage (icons, resizable, etc.).
     */
    public <C> C show(String fxmlPath, String title, Consumer<Stage> stageConfigurer, Consumer<C> controllerConfigurer) {
        return show(primary, fxmlPath, title, stageConfigurer, controllerConfigurer);
    }

    /**
     * Core show implementation that allows stage + controller customization.
     */
    public <C> C show(Stage stage,
                      String fxmlPath,
                      String title,
                      Consumer<Stage> stageConfigurer,
                      Consumer<C> controllerConfigurer) {
        Objects.requireNonNull(stage, "stage");
        Pair<Parent, C> pair = ctx.loadViewWithController(fxmlPath);
        Parent root = pair.getKey();
        C controller = pair.getValue();
        FxViewSupport.onLoaded(controller, ctx, root);

        ctx.runOnFxThread(() -> {
            FxViewSupport.onHidden(activeController);
            Scene scene = stage.getScene();
            if (scene == null) {
                scene = new Scene(root);
                applyGlobalStyles(scene);
                ThemeManager.getInstance().applyActiveTheme(scene);
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
                applyGlobalStyles(scene); // ensure globals present if scene was created elsewhere
                ThemeManager.getInstance().applyActiveTheme(scene);
            }
            if (title != null) stage.setTitle(title);
            if (stageConfigurer != null) stageConfigurer.accept(stage);
            if (!stage.isShowing()) stage.show();
            stage.toFront();
            FxViewSupport.onShown(controller, ctx, stage, this, root);
            activeController = controller;
        });

        if (controllerConfigurer != null && controller != null) {
            controllerConfigurer.accept(controller);
        }
        return controller;
    }

    /**
     * Returns the controller backing the currently visible view, if any.
     */
    public Optional<Object> getActiveController() {
        return Optional.ofNullable(activeController);
    }

    /**
     * Returns the active controller when it matches the requested type.
     */
    public <C> Optional<C> getActiveController(Class<C> type) {
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(activeController)
                .filter(type::isInstance)
                .map(type::cast);
    }

    /**
     * Show a blocking modal dialog from FXML; returns the dialog stage and controller.
     */
    public <C> Pair<Stage, C> modal(String fxmlPath, String title, Consumer<Stage> dialogConfigurer) {
        Pair<Parent, C> pair = this.ctx.loadViewWithController(fxmlPath);
        Parent root = pair.getKey();

        Stage owner = this.primary;
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title == null ? "" : title);

        Scene scene = new Scene(root);

        // 1) copy styles from owner scene
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        // 2) also apply whatever the context thinks is global
        this.applyGlobalStyles(scene);

        dialog.setScene(scene);

        if (dialogConfigurer != null) {
            dialogConfigurer.accept(dialog);
        }

        Platform.runLater(dialog::showAndWait);
        return new Pair<>(dialog, pair.getValue());
    }


    private void applyGlobalStyles(Scene scene) {
        ctx.applyGlobalStylesheets(scene);
    }


}
