package dev.codecounty.springai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document metadata, served at {@code /v3/api-docs} and rendered at
 * {@code /swagger-ui.html}.
 *
 * <p>The description carries the two behaviours a caller cannot infer from the schemas and
 * will otherwise get wrong: that a guardrail refusal is a <b>200</b> rather than a 4xx, and
 * that filing search defaults to the latest edition only.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockAgentOpenApi(@Value("${server.port:8081}") String port) {
        return new OpenAPI()
                .info(new Info()
                        .title("Stock Research Agent API")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                Answers questions about US-listed companies from two grounded sources: \
                                live market data and the company's own SEC filings (10-K, 10-Q) indexed \
                                in Postgres + pgvector. The agent never answers a factual question from \
                                model memory — prices come from a tool call, filing claims come from \
                                retrieved passages and are cited.

                                **Guardrails return 200, not 4xx.** A refused question (investment advice, \
                                prompt injection) is a valid conversational turn: the response carries the \
                                refusal text plus `guardrailBlocked: true` and a `guardrailCategory`. Check \
                                that flag rather than the status code to detect a refusal.

                                **Filing search defaults to the latest edition.** Successive filings \
                                contradict each other by design, so `versionScope` is `LATEST` unless you \
                                pass `ALL` or an explicit `fiscalYear`.

                                **Ingestion is deduplicated by content hash.** Re-uploading identical bytes \
                                returns `status: DUPLICATE_SKIPPED` and re-embeds nothing.

                                Errors are RFC 9457 problem details. Upstream model provider messages are \
                                never forwarded — they can contain masked credentials or prompt content.""")
                        .contact(new Contact().name("JSpringVerse"))
                        .license(new License().name("Apache 2.0")))
                .servers(List.of(new Server()
                        .url("http://localhost:" + port)
                        .description("Local development")))
                .tags(List.of(
                        new Tag().name("Agent")
                                .description("Ask the research agent. It decides which market and filing tools to call."),
                        new Tag().name("Filings")
                                .description("Ingest, inspect, search and remove the SEC filings backing retrieval."),
                        new Tag().name("Sandbox")
                                .description("Local-model tool-calling playground. Not part of the agent.")));
    }
}
