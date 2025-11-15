package com.zephyrstack.fxlib.networking;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of an HTTP request. Use {@link Builder} shortcuts such as {@link #get(String)}
 * or {@link #post(String)} to prepare a request with query parameters, headers, and body content.
 */
public final class RestRequest {
    private final String method;
    private final String pathOrUrl;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final String body;
    private final String contentType;
    private final Duration timeout;

    private RestRequest(Builder builder) {
        this.method = builder.method;
        this.pathOrUrl = builder.pathOrUrl;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(builder.queryParams));
        this.body = builder.body;
        this.contentType = builder.contentType;
        this.timeout = builder.timeout;
    }

    public String method() { return method; }
    public String pathOrUrl() { return pathOrUrl; }
    public Map<String, String> headers() { return headers; }
    public Map<String, String> queryParams() { return queryParams; }
    public Optional<String> body() { return Optional.ofNullable(body); }
    public Optional<String> contentType() { return Optional.ofNullable(contentType); }
    public Optional<Duration> timeout() { return Optional.ofNullable(timeout); }

    // ---- Builder helpers ----

    public static Builder get(String pathOrUrl) { return new Builder("GET", pathOrUrl); }
    public static Builder post(String pathOrUrl) { return new Builder("POST", pathOrUrl); }
    public static Builder put(String pathOrUrl) { return new Builder("PUT", pathOrUrl); }
    public static Builder patch(String pathOrUrl) { return new Builder("PATCH", pathOrUrl); }
    public static Builder delete(String pathOrUrl) { return new Builder("DELETE", pathOrUrl); }

    public static final class Builder {
        private final String method;
        private final String pathOrUrl;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private String body;
        private String contentType;
        private Duration timeout;

        private Builder(String method, String pathOrUrl) {
            this.method = Objects.requireNonNull(method, "method").toUpperCase();
            this.pathOrUrl = Objects.requireNonNull(pathOrUrl, "pathOrUrl");
        }

        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder headers(Map<String, String> map) {
            if (map != null) map.forEach(this::header);
            return this;
        }

        public Builder queryParam(String name, String value) {
            queryParams.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder queryParams(Map<String, String> params) {
            if (params != null) params.forEach(this::queryParam);
            return this;
        }

        public Builder body(String content, String contentType) {
            this.body = Objects.requireNonNull(content, "content");
            this.contentType = contentType;
            return this;
        }

        public Builder jsonBody(String json) {
            return body(json, "application/json");
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public RestRequest build() {
            return new RestRequest(this);
        }
    }
}
