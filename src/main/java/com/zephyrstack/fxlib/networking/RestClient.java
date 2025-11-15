package com.zephyrstack.fxlib.networking;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Lightweight wrapper around {@link java.net.http.HttpClient} that takes care of base URLs,
 * default headers, query parameters and multi-method convenience helpers.
 */
public final class RestClient {
    @Override
    public String toString() {
        return "RestClient{" +
                "httpClient=" + httpClient +
                ", baseUri=" + baseUri +
                ", defaultHeaders=" + defaultHeaders +
                ", defaultTimeout=" + defaultTimeout +
                '}';
    }

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Map<String, String> defaultHeaders;
    private final Duration defaultTimeout;

    private RestClient(Builder builder) {
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder()
                .connectTimeout(builder.defaultTimeout != null ? builder.defaultTimeout : Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.baseUri = builder.baseUri;
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(builder.defaultHeaders));
        this.defaultTimeout = builder.defaultTimeout != null ? builder.defaultTimeout : Duration.ofSeconds(30);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    // ---- Convenience methods ----
    public RestResponse get(String path) throws IOException, InterruptedException {
        return send(RestRequest.get(path).build());
    }

    public RestResponse delete(String path) throws IOException, InterruptedException {
        return send(RestRequest.delete(path).build());
    }

    public RestResponse post(String path, String body, String contentType) throws IOException, InterruptedException {
        return send(RestRequest.post(path).body(body, contentType).build());
    }

    public RestResponse put(String path, String body, String contentType) throws IOException, InterruptedException {
        return send(RestRequest.put(path).body(body, contentType).build());
    }

    public RestResponse patch(String path, String body, String contentType) throws IOException, InterruptedException {
        return send(RestRequest.patch(path).body(body, contentType).build());
    }

    // ---- Core send helpers ----
    public RestResponse send(RestRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = buildRequest(request);
        HttpResponse<String> httpResponse =
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return toRestResponse(httpResponse);
    }

    public CompletableFuture<RestResponse> sendAsync(RestRequest request) {
        HttpRequest httpRequest = buildRequest(request);
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::toRestResponse);
    }

    private RestResponse toRestResponse(HttpResponse<String> response) {
        return new RestResponse(
                response.statusCode(),
                response.body(),
                response.headers(),
                response.uri(),
                response.version());
    }

    private HttpRequest buildRequest(RestRequest restRequest) {
        Objects.requireNonNull(restRequest, "restRequest");
        HttpRequest.Builder builder = HttpRequest.newBuilder();

        URI uri = appendQuery(resolveUri(restRequest.pathOrUrl()), restRequest.queryParams());
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Resolved URI must be absolute. Provide a baseUri or absolute path.");
        }
        builder.uri(uri);

        Duration timeout = restRequest.timeout().orElse(defaultTimeout);
        if (timeout != null) builder.timeout(timeout);

        HttpRequest.BodyPublisher publisher = restRequest.body()
                .map(body -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .orElseGet(HttpRequest.BodyPublishers::noBody);
        builder.method(restRequest.method(), publisher);

        Map<String, String> mergedHeaders = new LinkedHashMap<>(defaultHeaders);
        mergedHeaders.putAll(restRequest.headers());
        if (restRequest.body().isPresent()) {
            String contentType = restRequest.contentType().orElse("text/plain; charset=UTF-8");
            mergedHeaders.putIfAbsent("Content-Type", contentType);
        }
        mergedHeaders.forEach(builder::header);

        return builder.build();
    }

    private URI resolveUri(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            if (baseUri == null) throw new IllegalArgumentException("No path provided and baseUri is not set.");
            return baseUri;
        }
        URI candidate = URI.create(pathOrUrl);
        if (candidate.isAbsolute()) return candidate;
        if (baseUri == null) {
            throw new IllegalArgumentException("Relative paths require that baseUri is configured.");
        }
        return baseUri.resolve(pathOrUrl);
    }

    private URI appendQuery(URI base, Map<String, String> params) {
        if (params.isEmpty()) return base;
        String encoded = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String combinedQuery = base.getQuery();
        combinedQuery = (combinedQuery == null || combinedQuery.isBlank())
                ? encoded
                : combinedQuery + "&" + encoded;
        try {
            return new URI(base.getScheme(), base.getAuthority(), base.getPath(), combinedQuery, base.getFragment());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Unable to append query parameters to URI: " + base, ex);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- Builder ----
    public static final class Builder {
        private HttpClient httpClient;
        private URI baseUri;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private Duration defaultTimeout;

        private Builder() {
        }

        public Builder baseUri(String baseUri) {
            if (baseUri != null) {
                this.baseUri = URI.create(baseUri);
            }
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public Builder defaultHeader(String name, String value) {
            defaultHeaders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder defaultHeaders(Map<String, String> headers) {
            if (headers != null) headers.forEach(this::defaultHeader);
            return this;
        }

        public Builder defaultTimeout(Duration timeout) {
            this.defaultTimeout = timeout;
            return this;
        }

        public Builder httpClient(HttpClient client) {
            this.httpClient = client;
            return this;
        }

        public RestClient build() {
            return new RestClient(this);
        }
    }
}
