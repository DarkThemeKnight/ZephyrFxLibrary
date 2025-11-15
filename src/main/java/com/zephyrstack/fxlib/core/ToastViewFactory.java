package com.zephyrstack.fxlib.core;

import javafx.scene.Node;

public interface ToastViewFactory {
    /**
     * Create a toast node for the given message.
     * The node should already be styled; Toast will position it.
     */
    Node createToast(String message) throws Exception;
}
