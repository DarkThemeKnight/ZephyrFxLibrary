# RestClient POST request sample

Use `RestClient` when you want a lightweight wrapper over `java.net.http.HttpClient` with
optional base URLs, default headers, and per-call overrides.

```java
import com.zephyrstack.fxlib.networking.RestClient;
import com.zephyrstack.fxlib.networking.RestRequest;
import com.zephyrstack.fxlib.networking.RestResponse;

import java.time.Duration;
import java.util.UUID;

public class RestClientPostSample {
    public static void main(String[] args) throws Exception {
        RestClient client = RestClient.newBuilder()
                .baseUri("https://api.example.com/v1/")
                .defaultHeader("Authorization", "Bearer <token>")
                .defaultTimeout(Duration.ofSeconds(10))
                .build();

        RestRequest request = RestRequest.post("orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .queryParam("locale", "en-US")
                .jsonBody("""
                        {
                          "sku": "ABC-123",
                          "quantity": 2,
                          "priority": true
                        }
                        """)
                .build();

        RestResponse response = client.send(request);

        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body());
    }
}
```

`RestClient.post(...)` is a shortcut for `send(RestRequest.post(...).body(...))`, so you can
swap in the simpler helper if you don’t need extra headers or query parameters.
