package com.zephyrstack.fxlib.core;

import javafx.scene.Scene;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers named themes (groups of stylesheets) and applies them to scenes.
 */
public final class ThemeManager {
    private static volatile ThemeManager instance;

    private final ZephyrFxApplicationContext ctx;
    private final Map<String, Theme> themes = new ConcurrentHashMap<>();
    private final Map<Scene, String> appliedThemes = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile String activeThemeName;

    private ThemeManager(ZephyrFxApplicationContext ctx) {
        this.ctx = ctx;
    }

    public static ThemeManager getInstance() {
        ZephyrFxApplicationContext ctx = ZephyrFxApplicationContext.getInstance();
        if (instance == null) {
            synchronized (ThemeManager.class) {
                if (instance == null) {
                    instance = new ThemeManager(ctx);
                }
            }
        }
        return instance;
    }

    public Theme registerTheme(String name, List<String> stylesheetPaths) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(stylesheetPaths, "stylesheetPaths");
        List<String> urls = stylesheetPaths.stream()
                .map(this::resolveStylesheet)
                .filter(Objects::nonNull)
                .toList();
        Theme theme = new Theme(name, urls);
        themes.put(name, theme);
        if (activeThemeName == null) {
            activeThemeName = name;
        }
        return theme;
    }

    public Theme registerTheme(String name, String... stylesheetPaths) {
        return registerTheme(name, List.of(stylesheetPaths));
    }

    public Optional<Theme> getTheme(String name) {
        return Optional.ofNullable(themes.get(name));
    }

    public void unregisterTheme(String name) {
        themes.remove(name);
        if (Objects.equals(activeThemeName, name)) {
            activeThemeName = null;
        }
    }

    public void setActiveTheme(String name) {
        if (name == null || !themes.containsKey(name)) {
            throw new IllegalArgumentException("Unknown theme: " + name);
        }
        this.activeThemeName = name;
    }

    public Optional<Theme> activeTheme() {
        return Optional.ofNullable(activeThemeName).map(themes::get);
    }

    public void applyActiveTheme(Scene scene) {
        if (scene == null) return;
        activeTheme().ifPresent(theme -> applyTheme(scene, theme.name()));
    }

    public void applyTheme(Scene scene, String themeName) {
        Objects.requireNonNull(scene, "scene");
        Theme theme = themes.get(themeName);
        if (theme == null) return;

        ctx.runOnFxThread(() -> {
            String previouslyApplied = appliedThemes.get(scene);
            if (previouslyApplied != null && !previouslyApplied.equals(themeName)) {
                Theme previous = themes.get(previouslyApplied);
                if (previous != null) {
                    scene.getStylesheets().removeAll(previous.stylesheetUrls());
                }
            }
            theme.stylesheetUrls().forEach(url -> {
                if (!scene.getStylesheets().contains(url)) {
                    scene.getStylesheets().add(url);
                }
            });
            appliedThemes.put(scene, themeName);
        });
    }

    private String resolveStylesheet(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.contains("://")) return path;
        try {
            URL url = ctx.resolveResource(path);
            return url.toExternalForm();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record Theme(String name, List<String> stylesheetUrls) {
        public Theme {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(stylesheetUrls, "stylesheetUrls");
        }
    }
}
