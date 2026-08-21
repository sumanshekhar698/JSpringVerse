package dev.codecounty.springai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full context startup: chat models, tools, pgvector store and schema initialisation.
 *
 * <p>Opt-in, because it needs infrastructure the unit tests deliberately do not: a running
 * Postgres with the {@code vector} extension, and {@code OPENAI_API_KEY} set. Enable it
 * once your environment is configured:
 *
 * <pre>
 *   $env:POSTGRES_TEST = "true"; .\mvnw.cmd test -Dtest=SpringBootAiApplicationTests
 * </pre>
 *
 * <p>It is worth running before any deploy — it is what catches a wrong embedding
 * dimension, a missing extension or an unresolvable bean, all of which otherwise surface
 * as a failed startup in the target environment.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST", matches = "true")
class SpringBootAiApplicationTests {

    @Test
    void contextLoads() {
    }

}
