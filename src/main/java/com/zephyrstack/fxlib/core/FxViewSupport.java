package com.zephyrstack.fxlib.core;

import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * Internal utilities to bridge StageRouter/FXML loaders with controller lifecycle hooks.
 */
final class FxViewSupport {
    private FxViewSupport() {}

    static void onLoaded(Object controller,
                         ZephyrFxApplicationContext ctx,
                         Parent root) {
        if (controller instanceof FxController fxController) {
            fxController.setViewContext(new FxViewContext(ctx, null, null, root));
            fxController.onViewLoaded();
        }
    }

    static void onShown(Object controller,
                        ZephyrFxApplicationContext ctx,
                        Stage stage,
                        StageRouter router,
                        Parent root) {
        if (controller instanceof FxController fxController) {
            fxController.setViewContext(new FxViewContext(ctx, stage, router, root));
            fxController.onViewShown();
        }
    }

    static void onHidden(Object controller) {
        if (controller instanceof FxController fxController) {
            fxController.onViewHidden();
        }
    }
}
