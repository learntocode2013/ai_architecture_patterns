***

# AI Gateway Architecture Practice Session

## User Story & Acceptance Criteria

**Title:** Implement a Resilient AI Gateway for LLM Integration

**User Story:** As a Staff Engineer focused on evolving system architecture, I want to deploy and configure an AI Gateway using LiteLLM and Redis, so that I can evaluate patterns for high availability, automated failovers, and semantic caching in AI-driven development.

**Acceptance Criteria:**
1.  **Gateway Deployment:** A local LiteLLM container is running and accessible via `http://localhost:4000`.
2.  **Semantic Caching:** A Redis Stack container is deployed alongside the gateway, with LiteLLM configured to cache semantically similar prompts to reduce external API calls.
3.  **Failover Routing:** The gateway is configured with a primary model and a secondary fallback model to ensure resilience against provider outages.
4.  **Client Integration:** A native Java HTTP client successfully routes a standard OpenAI-formatted request through the gateway and receives a valid completion.
5.  **Cache Verification:** Vector embeddings and cache hits are visually verified using the RedisInsight UI.

---

## 1. Objective
This exercise serves as a practical milestone for mastering AI-driven system design. The goal is to decouple core application logic—especially critical in high-throughput analytics and communications platforms—from third-party LLM providers by introducing a centralized control plane. This architecture absorbs the complexities of vendor-specific APIs, transient failures, and repeated query latency, allowing the internal system design to remain clean and scalable.



## 2. Prerequisites
* Docker and Docker Compose installed.
* Java Development Kit (JDK) 11 or higher.
* API Keys for at least two LLM providers (e.g., OpenAI, Google Gemini, or Anthropic) for testing failovers. *(Note: You can use invalid keys to intentionally force a failover during testing.)*

## 3. Environment Setup (`docker-compose.yml`)
To support both the gateway and semantic caching, we will use Docker Compose to spin up LiteLLM and Redis Stack (which includes the vector search capabilities needed for semantic caching and the RedisInsight UI).

Create a `docker-compose.yml` file in your working directory:

```yaml
version: '3.8'

services:
  litellm:
    image: ghcr.io/berriai/litellm:main-latest
    container_name: litellm-gateway
    ports:
      - "4000:4000"
    volumes:
      - ./config.yaml:/app/config.yaml
    environment:
      - OPENAI_API_KEY=your-openai-key
      - GEMINI_API_KEY=your-gemini-key
      - REDIS_HOST=redis-stack
      - REDIS_PASSWORD=dummy-password
    command: [ "--config", "/app/config.yaml" ]
    depends_on:
      - redis-stack

  redis-stack:
    image: redis/redis-stack:latest
    container_name: redis-vector-cache
    ports:
      - "6379:6379"
      - "8001:8001" # RedisInsight UI for inspecting cache hits
    environment:
      - REDIS_ARGS=--requirepass dummy-password
```

## 4. Gateway Configuration (`config.yaml`)
This configuration establishes the routing aliases, the fallback chain for high availability, and the Redis connection for semantic caching.

Create a `config.yaml` file in the same directory:

```yaml
model_list:
  # Primary Model with Failover
  - model_name: enterprise-chat
    litellm_params:
      model: openai/gpt-3.5-turbo
      api_key: "os.environ/OPENAI_API_KEY"
      fallbacks: ["backup-chat"]

  # Fallback Model
  - model_name: backup-chat
    litellm_params:
      model: gemini/gemini-1.5-pro
      api_key: "os.environ/GEMINI_API_KEY"

litellm_settings:
  # Enable Redis Semantic Caching globally
  cache: true
  cache_params:
    type: redis-semantic
    redis_host: "os.environ/REDIS_HOST"
    redis_port: 6379
    redis_password: "os.environ/REDIS_PASSWORD"
    similarity_threshold: 0.95

general_settings:
  master_key: "sk-local-practice-key"
```

## 5. Java Client Integration (`GatewayClient.java`)
The following Java application uses the native `HttpClient` to interact with the gateway, remaining entirely agnostic to the complex failover and caching logic happening downstream.

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GatewayClient {

    private static final String GATEWAY_URL = "http://localhost:4000/v1/chat/completions";
    private static final String GATEWAY_API_KEY = "sk-local-practice-key";

    public static void main(String[] args) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Target the abstract 'enterprise-chat' route defined in config.yaml
        String jsonPayload = """
            {
              "model": "enterprise-chat",
              "messages": [
                {
                  "role": "user",
                  "content": "Explain the concept of event sourcing in distributed systems."
                }
              ]
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GATEWAY_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GATEWAY_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            long startTime = System.currentTimeMillis();
            System.out.println("Transmitting request to AI Gateway...");
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            long endTime = System.currentTimeMillis();
            System.out.println("Status: " + response.statusCode());
            System.out.println("Latency: " + (endTime - startTime) + "ms");
            System.out.println("Response: " + response.body());
            
        } catch (Exception e) {
            System.err.println("Gateway communication failed: " + e.getMessage());
        }
    }
}
```

## 6. Execution Steps
1.  **Initialize Infrastructure:** Run `docker-compose up -d` in your terminal to start LiteLLM and Redis.
2.  **Verify Gateway:** Check the Docker logs (`docker logs litellm-gateway`) to ensure it connected to Redis and loaded the `config.yaml` successfully.
3.  **Execute Baseline:** Compile and run `GatewayClient.java`. Note the latency (e.g., ~1500ms) for the initial generation.
4.  **Test Semantic Caching:** Run `GatewayClient.java` a second time with the exact same prompt. Observe the latency drop significantly (e.g., ~20ms) as the response is served from the Redis vector cache.
5.  **Test Failover:** Alter the `config.yaml` to include an invalid `OPENAI_API_KEY` to simulate a provider outage. Restart the LiteLLM container. Run the Java client again; the request should take slightly longer as it hits the OpenAI error, but it will ultimately succeed by falling back to the Gemini model.

## 7. RedisInsight Test Plan (Semantic Caching Verification)
To truly understand how semantic caching works under the hood, we can inspect the vector embeddings generated by LiteLLM directly inside our Redis database.



**Steps to Verify:**
1.  **Access the UI:** Open your web browser and navigate to `http://localhost:8001`. This connects to the RedisInsight container deployed via our Docker Compose file.
2.  **Connect to the Database:** If prompted, connect to the local Redis instance using the host `redis-stack`, port `6379`, and the password `dummy-password` (as defined in our setup).
3.  **Run the Java Client (Initial Query):** Execute your `GatewayClient.java` application. The prompt *"Explain the concept of event sourcing in distributed systems"* will be sent to the gateway.
4.  **Inspect the Cache Key:** In RedisInsight, click on the **Browser** tab. You will see a new key generated by LiteLLM (usually prefixed with `cache:` or similar, depending on the LiteLLM version).
5.  **Analyze the Payload:** Click on the key to view its contents. You will see two critical pieces of data:
    * **The Vector Embedding:** A long array of floating-point numbers. This is the mathematical representation of your prompt's semantic meaning.
    * **The Cached Response:** The actual text generated by the LLM, stored as a string or JSON object alongside the vector.
6.  **Run a Semantic Variation:** Modify the `jsonPayload` in your `GatewayClient.java` to ask the same question differently, for example: *"What is event sourcing in a distributed architecture?"*
7.  **Verify the Cache Hit:** Run the Java client again. Despite the string being different, the latency should remain extremely low (~20ms). Back in RedisInsight, you won't see a new completion generated; the gateway calculated the vector for the new prompt, recognized it was >95% similar to the existing embedding, and returned the cached response.

***

Would you like to explore adding OpenTelemetry to this setup next, so you can trace the exact lifecycle of these requests from the Java client through the gateway and down to the cache?