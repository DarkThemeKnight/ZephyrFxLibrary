package com.zephyrstack.fxlib.core;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared metadata about the currently visible view, injected into {@link FxController}s so they can
 * access the context, stage, router, or root without tight coupling.
 */
public record FxViewContext(
        ZephyrFxApplicationContext applicationContext,
        Stage stage,
        StageRouter stageRouter,
        Parent root) {

    public FxViewContext {
        Objects.requireNonNull(applicationContext, "applicationContext");
    }

    public Optional<Stage> stageOptional() {
        return Optional.ofNullable(stage);
    }

    public Optional<StageRouter> stageRouterOptional() {
        return Optional.ofNullable(stageRouter);
    }

    public Scene scene() {
        return stage == null ? null : stage.getScene();
    }
}
