package dev.codecounty.springai.health;

import org.springframework.beans.factory.annotation.Value;
//import org.springframework.boot.actuate.health.Health;
//import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("ollamaHealthIndicator")
public class OllamaHealthIndicator implements HealthIndicator {

    private final String baseUrl;
    private final RestClient client = RestClient.create();

    public OllamaHealthIndicator(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Health health() {
        try {
            client.get().uri(baseUrl + "/api/tags").retrieve().toBodilessEntity();
            return Health.up().withDetail("endpoint", baseUrl).build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("endpoint", baseUrl).build();
        }
    }
}