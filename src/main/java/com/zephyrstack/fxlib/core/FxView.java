package com.zephyrstack.fxlib.core;

import javafx.scene.Parent;

/**
 * Tuple returned by {@link FxViewLoader} containing the loaded root and controller.
 */
public record FxView<C>(Parent root, C controller) {}
