package com.zephyrstack.fxlib.core;

/**
 * Marker abstraction for FXML controllers that want lifecycle callbacks without inheriting directly
 * from JavaFX {@code Application}. Implementers opt-in to the hooks they need.
 */
public interface FxController {

    /**
     * Called right after the controller has been constructed via FXMLLoader and its view graph loaded.
     */
    default void onViewLoaded() {}

    /**
     * Called after the controller's root has been attached to a Stage via {@link StageRouter}.
     */
    default void onViewShown() {}

    /**
     * Called immediately before the view is replaced/hidden within the StageRouter.
     */
    default void onViewHidden() {}

    /**
     * Provides metadata about the current view (context, stage, router, root).
     */
    default void setViewContext(FxViewContext context) {}
}
