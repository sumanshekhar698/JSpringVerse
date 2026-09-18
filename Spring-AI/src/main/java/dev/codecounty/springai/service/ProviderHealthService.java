package dev.codecounty.springai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProviderHealthService {

    private final RestClient restClient;
    private final String ollamaBaseUrl;
    private final String openAiApiKey;

    public ProviderHealthService(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String openAiApiKey
    ) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.openAiApiKey = openAiApiKey;

        // Set short timeouts so health checks fail fast (1.5s timeout)
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(1500));
        requestFactory.setReadTimeout(Duration.ofMillis(1500));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public Map<String, Object> checkProviders() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ollama", checkOllama());
        status.put("openai", checkOpenAi());
        return status;
    }

    private Map<String, Object> checkOllama() {
        try {
            // Ping Ollama's local tags/version endpoint
            restClient.get()
                    .uri(ollamaBaseUrl + "/api/tags")
                    .retrieve()
                    .toBodilessEntity();

            return Map.of("status", "UP", "endpoint", ollamaBaseUrl);
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }

    private Map<String, Object> checkOpenAi() {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            return Map.of("status", "NOT_CONFIGURED", "message", "API key missing");
        }

        try {
            // Ping OpenAI's lightweight metadata endpoint (List Models)
            restClient.get()
                    .uri("https://api.openai.com/v1/models")
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .retrieve()
                    .toBodilessEntity();

            return Map.of("status", "UP");
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }
}