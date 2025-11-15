package com.zephyrstack.fxlib.networking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;

/**
 * Value object that represents the result of an HTTP call performed by {@link RestClient}.
 */
public record RestResponse(
        int statusCode,
        String body,
        HttpHeaders headers,
        URI uri,
        HttpClient.Version version) {

    /**
     * Indicates whether the status code is within the 2xx range.
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
