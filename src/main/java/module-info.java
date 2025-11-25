module JavaFxHelperibrary {
    requires java.net.http;
    requires javafx.base;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires org.kordamp.ikonli.javafx;


    opens com.zephyrstack.fxlib.controllers to javafx.fxml;

    exports com.zephyrstack.fxlib.core;
    exports com.zephyrstack.fxlib.controllers;
    exports com.zephyrstack.fxlib.concurrent;
    exports com.zephyrstack.fxlib.networking;
    exports com.zephyrstack.fxlib.control.controls.table;
    exports com.zephyrstack.fxlib.control;
}