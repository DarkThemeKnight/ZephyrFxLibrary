package com.zephyrstack.fxlib.core;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.util.Callback;
import javafx.util.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Central runtime context for ZephyrFX modules.
 */
public final class ZephyrFxApplicationContext implements AutoCloseable {
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();
    private static final ThreadFactory WORKER_FACTORY = r -> {
        Thread t = new Thread(r, "zephyrfx-worker-" + WORKER_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    };
    private static final Executor FX = command -> {
        if (Platform.isFxApplicationThread()) command.run();
        else Platform.runLater(command);
    };

    private static volatile ZephyrFxApplicationContext instance;

    private final Application application;
    private final Map<String, Object> sharedState;
    private final ExecutorService backgroundExecutor;
    private final CopyOnWriteArrayList<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> globalStylesheets = new CopyOnWriteArrayList<>();
    private final Map<String, WeakReference<Parent>> fxmlCache = new WeakHashMap<>();
    private final CopyOnWriteArrayList<ClassLoader> resourceLoaders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Class<?>> resourceAnchors = new CopyOnWriteArrayList<>();
    private volatile boolean cacheViews = false;
    private volatile Stage primaryStage;

    private ZephyrFxApplicationContext(Application application,
                                       Stage primaryStage,
                                       Map<String, Object> initialState) {
        this.application = application;
        this.primaryStage = primaryStage;
        this.sharedState = new ConcurrentHashMap<>(initialState == null ? Map.of() : initialState);
        this.backgroundExecutor = Executors.newCachedThreadPool(WORKER_FACTORY);
        registerResourceLoader(ZephyrFxApplicationContext.class.getClassLoader());
        registerResourceAnchor(ZephyrFxApplicationContext.class);
        registerResourceLoader(Thread.currentThread().getContextClassLoader());
        registerResourceLoader(ClassLoader.getSystemClassLoader());
        if (application != null) {
            registerResourceLoader(application.getClass().getClassLoader());
            registerResourceAnchor(application.getClass());
        }
    }

    // ---------- lifecycle ----------
    public static synchronized void initialize(Application application) {
        initialize(application, null, Map.of());
    }

    public static synchronized void initialize(Application application,
                                               Stage primaryStage,
                                               Map<String, Object> initialState) {
        Objects.requireNonNull(application, "application");
        if (instance != null) throw new IllegalStateException("ZephyrFxApplicationContext already initialized");
        instance = new ZephyrFxApplicationContext(application, primaryStage, initialState);
    }

    public static ZephyrFxApplicationContext getInstance() {
        ZephyrFxApplicationContext ctx = instance;
        if (ctx == null) throw new IllegalStateException("ZephyrFxApplicationContext is not initialized yet");
        return ctx;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    // ---------- accessors ----------
    public Application getApplication() {
        return application;
    }

    public Application.Parameters getParameters() {
        return application.getParameters();
    }

    public Optional<Stage> getPrimaryStage() {
        return Optional.ofNullable(primaryStage);
    }

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    public static Executor fxExecutor() {
        return FX;
    }

    public Optional<Object> getSharedValue(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(sharedState.get(key));
    }

    public void putSharedValue(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) sharedState.remove(key);
        else sharedState.put(key, value);
    }

    // ---------- resources ----------
    public void registerResourceLoader(ClassLoader loader) {
        if (loader != null) {
            resourceLoaders.remove(loader);
            resourceLoaders.addFirst(loader); // put at front
        }
    }

    /**
     * Registers a class to use as a lookup anchor for relative resource paths and JPMS-aware resource resolution.
     */
    public void registerResourceAnchor(Class<?> anchor) {
        if (anchor != null) {
            resourceAnchors.remove(anchor);
            resourceAnchors.addFirst(anchor); // put at front
        }
    }

    public URL resolveResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        URL asUrl = tryCreateUrl(resourcePath);
        if (asUrl != null) return asUrl;

        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        String absolutePath = resourcePath.startsWith("/") ? resourcePath : "/" + normalized;

        URL url = tryResolveWithAnchors(resourcePath, absolutePath, normalized);
        if (url == null) {
            url = tryResolveWithLoaders(normalized);
        }
        if (url == null) {
            url = ZephyrFxApplicationContext.class.getResource(absolutePath);
        }
        if (url == null) {
            url = ZephyrFxApplicationContext.class.getResource(normalized);
        }

        if (url == null) {
            throw new IllegalArgumentException("Resource not found on classpath: " + resourcePath);
        }
        return url;
    }

    public InputStream openResource(String resourcePath) {
        try {
            return resolveResource(resourcePath).openStream();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to open resource: " + resourcePath, ex);
        }
    }

    // ---------- FXML loading ----------

    /**
     * Original simple loader (no controller return).
     */
    public Parent loadView(String fxmlPath) {
        return loadViewInternal(fxmlPath, null, null);
    }

    /**
     * Controller-aware loader; returns (root, controller).
     */
    public <C> Pair<Parent, C> loadViewWithController(String fxmlPath) {
        return loadViewWithController(fxmlPath, null, null);
    }

    public <C> Pair<Parent, C> loadViewWithController(String fxmlPath,
                                                      ResourceBundle bundle,
                                                      Callback<Class<?>, Object> controllerFactory) {
        try {
            FXMLLoader loader = new FXMLLoader(resolveResource(fxmlPath));
            if (bundle != null) loader.setResources(bundle);
            if (controllerFactory != null) loader.setControllerFactory(controllerFactory);
            Parent root = maybeFromCache(fxmlPath, loader);
            @SuppressWarnings("unchecked")
            C controller = (C) loader.getController();
            return new Pair<>(root, controller);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load FXML: " + fxmlPath, ex);
        }
    }

    private Parent loadViewInternal(String fxmlPath,
                                    ResourceBundle bundle,
                                    Callback<Class<?>, Object> controllerFactory) {
        try {
            FXMLLoader loader = new FXMLLoader(resolveResource(fxmlPath));
            if (bundle != null) loader.setResources(bundle);
            if (controllerFactory != null) loader.setControllerFactory(controllerFactory);
            return maybeFromCache(fxmlPath, loader);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load FXML: " + fxmlPath, ex);
        }
    }

    private Parent maybeFromCache(String fxmlPath, FXMLLoader loader) throws IOException {
        if (!cacheViews) return loader.load();
        var ref = fxmlCache.get(fxmlPath);
        var cached = ref == null ? null : ref.get();
        if (cached != null) return cached;
        Parent root = loader.load();
        fxmlCache.put(fxmlPath, new WeakReference<>(root));
        return root;
    }

    /**
     * Enable/disable weak caching of loaded FXML roots. Disabled by default.
     */
    public void setCacheViews(boolean cache) {
        this.cacheViews = cache;
    }

    private URL tryResolveWithAnchors(String requestedPath, String absolutePath, String normalizedPath) {
        for (Class<?> anchor : resourceAnchors) {
            if (anchor == null) continue;
            URL url = null;
            if (!requestedPath.startsWith("/")) {
                url = anchor.getResource(requestedPath);
            }
            if (url == null) {
                url = anchor.getResource(absolutePath);
            }
            if (url == null) {
                url = tryResolveViaCodeSource(anchor, normalizedPath);
            }
            if (url != null) return url;
        }
        return null;
    }

    private URL tryResolveWithLoaders(String normalizedPath) {
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>(resourceLoaders);
        loaders.add(Thread.currentThread().getContextClassLoader());
        if (application != null) {
            loaders.add(application.getClass().getClassLoader());
        }
        loaders.add(ZephyrFxApplicationContext.class.getClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());

        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            URL url = loader.getResource(normalizedPath);
            if (url != null) return url;
        }
        return null;
    }

    private URL tryCreateUrl(String value) {
        try {
            return new URL(value);
        } catch (MalformedURLException ignored) {
            return null;
        }
    }

    // replace your current tryResolveViaCodeSource with this
    private URL tryResolveViaCodeSource(Class<?> anchor, String normalizedPath) {
        ProtectionDomain pd = anchor.getProtectionDomain();
        if (pd == null) return null;

        CodeSource cs = pd.getCodeSource();
        if (cs == null) return null;

        URL loc = cs.getLocation();
        if (loc == null) return null;

        try {
            if ("file".equals(loc.getProtocol())) {
                var uri  = loc.toURI();
                var path = Path.of(uri);

                if (Files.isDirectory(path)) {
                    // Dev/exploded output: resolve directly under classes dir
                    var candidate = path.resolve(normalizedPath);
                    if (Files.exists(candidate)) {
                        return candidate.toUri().toURL();
                    }
                    // Don’t fabricate a jar URL for directories
                    return null;
                } else {
                    // Likely a JAR; only construct jar: URL if the code source is a file that looks like a jar
                    var ext = path.getFileName().toString().toLowerCase();
                    if (ext.endsWith(".jar")) {
                        return new URL("jar:" + loc.toExternalForm() + "!/" + normalizedPath);
                    }
                    // Unknown file type — let other strategies try
                    return null;
                }
            }
            // Non-file protocols (e.g., jrt:) — let other strategies try
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    // ---------- scene + styles ----------
    public Scene loadScene(String fxmlPath, String... stylesheetPaths) {
        Parent root = loadView(fxmlPath);
        Scene scene = new Scene(root);
        applyGlobalStylesheets(scene);
        applyStylesheets(scene, stylesheetPaths);
        return scene;
    }


    public void registerGlobalStylesheet(String stylesheetPath) {
        String url = resolveStylesheetPath(stylesheetPath);
        if (url != null) globalStylesheets.addIfAbsent(url);
    }

    public void clearGlobalStylesheets() {
        globalStylesheets.clear();
    }

    public void applyStylesheets(Scene scene, String... stylesheetPaths) {
        Objects.requireNonNull(scene, "scene");
        if (stylesheetPaths == null || stylesheetPaths.length == 0) return;
        scene.getStylesheets().addAll(
                Arrays.stream(stylesheetPaths)
                        .map(this::resolveStylesheetPath)
                        .filter(Objects::nonNull)
                        .toList()
        );
    }

    public void replaceStylesheet(Scene scene, String existingStylesheet, String replacementStylesheet) {
        Objects.requireNonNull(scene, "scene");
        String oldUrl = resolveStylesheetPath(existingStylesheet);
        String newUrl = resolveStylesheetPath(replacementStylesheet);
        if (oldUrl != null) scene.getStylesheets().removeIf(s -> s.equals(oldUrl) || s.equals(existingStylesheet));
        if (newUrl != null) scene.getStylesheets().add(newUrl);
    }

    public void applyGlobalStylesheets(Scene scene) {
        if (globalStylesheets.isEmpty()) return;
        scene.getStylesheets().addAll(globalStylesheets);
    }

    private String resolveStylesheetPath(String stylesheetPath) {
        if (stylesheetPath == null || stylesheetPath.isBlank()) return null;
        if (stylesheetPath.contains("://")) return stylesheetPath;
        return resolveResource(stylesheetPath).toExternalForm();
    }

    // ---------- FX thread helpers ----------
    public void runOnFxThread(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (Platform.isFxApplicationThread()) task.run();
        else Platform.runLater(task);
    }

    // ---------- async (Task-free, CF-based) ----------
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return CompletableFuture.supplyAsync(work, backgroundExecutor);
    }

    /**
     * One-liner: work in background → deliver result on FX thread.
     */
    public <T> CompletableFuture<Void> supplyAsync(Supplier<T> work,
                                                   Consumer<T> uiSuccess,
                                                   Consumer<Throwable> uiError) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> cf = CompletableFuture.supplyAsync(work, backgroundExecutor);
        cf.thenAcceptAsync(res -> {
                    if (uiSuccess != null) uiSuccess.accept(res);
                }, FX)
                .exceptionally(ex -> {
                    if (uiError != null) FX.execute(() -> uiError.accept(unwrap(ex)));
                    return null;
                });
        return cf.thenAccept(r -> {
        }); // keep a handle for cancel() by caller
    }

    /**
     * Back-compat with your previous runAsync signature.
     */
    public <T> void runAsync(Supplier<T> backgroundWork,
                             Consumer<T> uiConsumer,
                             Consumer<Throwable> errorHandler) {
        supplyAsync(backgroundWork, uiConsumer, errorHandler);
    }

    private static Throwable unwrap(Throwable ex) {
        if (ex instanceof CompletionException && ex.getCause() != null) return ex.getCause();
        return ex;
    }


    /**
     * Convenience: load an image from a classpath/resource path into an ImageView.
     * Respects the ImageView's fitWidth/fitHeight if they are set (> 0).
     *
     * @param resourcePath path like "/images/invoice-qr.png" or "images/logo.png"
     * @param target       the ImageView to receive the image
     */
    public void loadImageInto(String resourcePath, ImageView target) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(target, "target");

        // resolve to URL using existing logic
        URL url = resolveResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("Image resource not found: " + resourcePath);
        }

        double fitW = target.getFitWidth();
        double fitH = target.getFitHeight();

        Image image;
        if (fitW > 0 || fitH > 0) {
            // scale to the ImageView's configured size, preserve ratio
            image = new Image(
                    url.toExternalForm(),
                    fitW > 0 ? fitW : 0,
                    fitH > 0 ? fitH : 0,
                    true,
                    true
            );
        } else {
            // load at natural size
            image = new Image(url.toExternalForm());
        }

        target.setImage(image);
    }


    // ---------- shutdown ----------
    public void addShutdownHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        shutdownHooks.add(hook);
    }

    public void onExit(Runnable hook) {
        addShutdownHook(hook);
    }

    public void shutdown() {
        shutdownHooks.forEach(this::runSafely);
        shutdownHooks.clear();
        backgroundExecutor.shutdownNow();
        instance = null;
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runSafely(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) { /* swallow on shutdown */ }
    }
}
