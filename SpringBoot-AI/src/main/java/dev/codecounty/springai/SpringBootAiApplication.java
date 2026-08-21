package dev.codecounty.springai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point.
 *
 * <p>{@link ConfigurationPropertiesScan} registers every {@code @ConfigurationProperties}
 * record under this package — {@code RagProperties}, {@code MarketDataProperties},
 * {@code GuardrailProperties} — in one place. Declaring them individually with
 * {@code @EnableConfigurationProperties} on scattered {@code @Configuration} classes is how
 * a new properties record ends up unregistered and the context fails to start with an
 * unhelpful "no qualifying bean" at the injection point rather than at the omission.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SpringBootAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootAiApplication.class, args);
    }

}
