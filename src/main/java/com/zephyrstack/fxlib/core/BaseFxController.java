package com.zephyrstack.fxlib.core;

import javafx.stage.Stage;

import java.util.Optional;

/**
 * Convenience base class for controllers that want quick access to {@link FxViewContext}.
 */
public abstract class BaseFxController implements FxController {
    private FxViewContext viewContext;

    @Override
    public void setViewContext(FxViewContext context) {
        this.viewContext = context;
    }

    public final FxViewContext viewContext() {
        return viewContext;
    }

    public final ZephyrFxApplicationContext applicationContext() {
        return viewContext == null ? ZephyrFxApplicationContext.getInstance() : viewContext.applicationContext();
    }

    public final Optional<Stage> stage() {
        return viewContext == null ? Optional.empty() : viewContext.stageOptional();
    }

    public final Optional<StageRouter> stageRouter() {
        return viewContext == null ? Optional.empty() : viewContext.stageRouterOptional();
    }
}
