package com.zephyrstack.fxlib.samples;

import com.zephyrstack.fxlib.core.DialogService;
import com.zephyrstack.fxlib.core.Toast;
import com.zephyrstack.fxlib.core.ZephyrFxApplicationContext;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Map;
import java.util.Objects;

/**
 * Small showcase that exercises the Toast helper with default rendering,
 * a per-call custom factory, and the DialogService convenience wrapper.
 */
public class ToastDemoApp extends Application {

    private StackPane toastHost;
    private final DialogService dialogService = new DialogService();

    @Override
    public void start(Stage primaryStage) {
        if (!ZephyrFxApplicationContext.isInitialized()) {
            ZephyrFxApplicationContext.initialize(this, primaryStage, Map.of());
        }
        ZephyrFxApplicationContext.getInstance().setPrimaryStage(primaryStage);

        toastHost = new StackPane();
        toastHost.setPadding(new Insets(36));

        VBox content = buildControls();
        toastHost.getChildren().add(content);
        StackPane.setAlignment(content, Pos.CENTER);

        Scene scene = new Scene(toastHost, 560, 360);
        scene.getStylesheets().add(requireStylesheet("toast-demo.css"));

        primaryStage.setTitle("Toast helper demo");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private VBox buildControls() {
        Label heading = new Label("Toast helper demo");
        heading.getStyleClass().add("demo-title");

        Label subtitle = new Label("Each button triggers a different toast scenario.");
        subtitle.getStyleClass().add("demo-subtitle");

        Button defaultToastButton = new Button("Default Toast.show()");
        defaultToastButton.setOnAction(e ->
                Toast.show(toastHost, "Draft saved successfully.", Duration.millis(2200)));

        Button customToastButton = new Button("Toast.show(...) with custom factory");
        customToastButton.setOnAction(e ->
                Toast.show(toastHost,
                        "Accent toast with icon!",
                        Duration.seconds(3),
                        ToastDemoApp::buildAccentToast));

        Button dialogServiceButton = new Button("DialogService.toast(...)");
        dialogServiceButton.setOnAction(e ->
                dialogService.toast(toastHost, "Queued background job", Duration.seconds(2.6)));

        VBox box = new VBox(12,
                heading,
                subtitle,
                defaultToastButton,
                customToastButton,
                dialogServiceButton);
        box.getStyleClass().add("demo-card");
        return box;
    }

    private static StackPane buildAccentToast(String message, String... additionalProperties) {
        Label label = new Label(message);
        label.getStyleClass().add("demo-toast-label");

        StackPane toast = new StackPane(label);
        toast.getStyleClass().add("demo-toast");
        return toast;
    }

    private String requireStylesheet(String name) {
        return Objects.requireNonNull(
                ToastDemoApp.class.getResource("/com/zephyrstack/fxlib/samples/" + name),
                "Missing demo stylesheet: " + name
        ).toExternalForm();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
