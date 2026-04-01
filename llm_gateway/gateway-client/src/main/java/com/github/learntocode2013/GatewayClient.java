package com.github.learntocode2013;

import io.vavr.control.Try;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GatewayClient {
    private static final Logger log = Logger.getLogger(GatewayClient.class.getSimpleName());
    public static final String GATEWAY_URI = "http://localhost:4000/v1/chat/completions";
    public static final String GATEWAY_API_KEY = "sk-local-practice-key";
    private final HttpClient client;

    public GatewayClient(int connectTimeoutSeconds) {
        this.client = initializeClient(Duration.ofSeconds(connectTimeoutSeconds));
    }

    private HttpClient initializeClient(Duration connTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connTimeout)
                .build();
    }

    private HttpRequest createRequest(String jsonPayload) {
        return HttpRequest
                .newBuilder(URI.create(GATEWAY_URI))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GATEWAY_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
    }

    public Try<String> submitRequest(String jsonPayload) {
        log.log(Level.INFO, "Sending request to AI gateway on URL: {0}", GATEWAY_URI);
        return Try.of(() -> {
            Instant start = Instant.now();
            HttpResponse<String> response = client.send(
                    createRequest(jsonPayload),
                    HttpResponse.BodyHandlers.ofString());
            long elapsedSeconds = Duration.between(start, Instant.now()).getSeconds();
            log.log(Level.INFO, "Received LLM response in {0} seconds", elapsedSeconds);
            return response.body();
        }).onFailure(e -> log.log(Level.SEVERE, "Request to LLM failed due to: {0}",
                e.getMessage()));
    }

    public static void main(String[] args) {
        String jsonPayload = """
                {
                    "model": "enterprise-chat",
                    "messages": [
                        {
                            "role": "user",
                            "content": "Explain the concept of event sourcing in distributed systems"
                        }
                    ]
                }
                """;
        var llmClient = new GatewayClient(10);
        llmClient.submitRequest(jsonPayload)
                .onSuccess(r -> log.log(Level.INFO, "Response: {0}", r));
    }
}
