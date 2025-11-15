# Toast helper demo

`ToastDemoApp` (`src/test/java/com/zephyrstack/fxlib/samples/ToastDemoApp.java`) is a tiny JavaFX scene that runs through the three primary ways to surface lightweight notifications:

1. `Toast.show(node, message)` – the stock styling that ships with the helper.
2. `Toast.show(node, message, duration, factory)` – inject a per-call `ToastViewFactory`.
3. `DialogService.toast(node, message, duration)` – when you already have a dialog service instance on hand.

The host node must live inside a `StackPane`, so the sample wraps its content with one `StackPane` and reuses it for every toast invocation.

## Running the demo

```bash
# compile the library + test sources so the sample app is available
mvn -q -DskipTests test-compile

# launch the JavaFX sample (pulls from target/classes + target/test-classes)
mvn -q \
  -Dexec.mainClass=com.zephyrstack.fxlib.samples.ToastDemoApp \
  -Dexec.classpathScope=test \
  exec:java
```

The scene loads `src/test/resources/com/zephyrstack/fxlib/samples/toast-demo.css`, which supplies the `zf-toast` base styles plus the accent variant showcased by the custom factory.

## Highlights

```java
// Default helper – uses the built-in styling.
Toast.show(toastHost, "Draft saved successfully.", Duration.millis(2200));

// Per-call toast factory for a custom surface.
Toast.show(
        toastHost,
        "Accent toast with icon!",
        Duration.seconds(3),
        ToastDemoApp::buildAccentToast
);

// DialogService convenience wrapper.
dialogService.toast(toastHost, "Queued background job", Duration.seconds(2.6));
```

To swap the visuals globally, call `Toast.setToastFactory(...)` once during application start-up and supply your preferred builder:

```java
Toast.setToastFactory(message -> {
    Label label = new Label(message);
    label.getStyleClass().add("demo-toast-label");
    StackPane surface = new StackPane(label);
    surface.getStyleClass().add("demo-toast");
    return surface;
});
```

You can use the sample’s CSS as a starting point for your own palette or drop in your project’s existing tokens.
