package dev.codecounty.springai.health;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component("openAiHealthIndicator")
public class OpenAiHealthIndicator implements HealthIndicator {

    private static final String OPENAI_MODELS_URL = "https://api.openai.com/v1/models";
    private final String apiKey;
    private final RestClient client;

    public OpenAiHealthIndicator(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.apiKey = apiKey;

        // Configure short timeouts so health checks fail fast if the network hangs
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(2000));
        requestFactory.setReadTimeout(Duration.ofMillis(2000));

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.unknown()
                    .withDetail("reason", "OpenAI API key is not configured")
                    .build();
        }

        try {
            client.get()
                    .uri(OPENAI_MODELS_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .toBodilessEntity();

            return Health.up()
                    .withDetail("provider", "OpenAI")
                    .withDetail("authenticated", true)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("provider", "OpenAI")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}