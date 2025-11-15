package com.zephyrstack.fxlib.core;

import javafx.scene.Parent;
import javafx.util.Callback;
import javafx.util.Pair;

import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Convenience loader that wraps {@link ZephyrFxApplicationContext#loadViewWithController(String)} and
 * automatically wires {@link FxController} lifecycle callbacks.
 */
public final class FxViewLoader {
    private final ZephyrFxApplicationContext ctx;

    public FxViewLoader() {
        this(ZephyrFxApplicationContext.getInstance());
    }

    public FxViewLoader(ZephyrFxApplicationContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    public <C> FxView<C> load(String fxmlPath) {
        return loadInternal(fxmlPath, null, null);
    }

    public <C> FxView<C> load(String fxmlPath,
                              ResourceBundle bundle) {
        return loadInternal(fxmlPath, bundle, null);
    }

    public <C> FxView<C> load(String fxmlPath,
                              ResourceBundle bundle,
                              Callback<Class<?>, Object> controllerFactory) {
        return loadInternal(fxmlPath, bundle, controllerFactory);
    }

    private <C> FxView<C> loadInternal(String fxmlPath,
                                       ResourceBundle bundle,
                                       Callback<Class<?>, Object> controllerFactory) {
        Pair<Parent, C> pair = ctx.loadViewWithController(fxmlPath, bundle, controllerFactory);
        FxViewSupport.onLoaded(pair.getValue(), ctx, pair.getKey());
        return new FxView<>(pair.getKey(), pair.getValue());
    }
}
