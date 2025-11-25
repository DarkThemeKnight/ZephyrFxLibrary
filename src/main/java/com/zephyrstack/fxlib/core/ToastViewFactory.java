package com.zephyrstack.fxlib.core;

import javafx.scene.Node;

public interface ToastViewFactory {
    Node createToast(String message, String... additionalProperties) throws Exception;
}
