package dev.codecounty.springai.config;

import dev.codecounty.springai.filings.RagProperties;
import dev.codecounty.springai.guardrails.GuardrailAdvisor;
import dev.codecounty.springai.guardrails.GuardrailProperties;
import dev.codecounty.springai.guardrails.GuardrailService;
import dev.codecounty.springai.memory.ChatMemoryProperties;
import dev.codecounty.springai.tools.FilingTools;
import dev.codecounty.springai.tools.StockTools;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The stock research agent.
 *
 * <p>Two clients are defined because the two jobs want different retrieval strategies:
 *
 * <ul>
 *   <li>{@code stockAgentChatClient} — the general agent. Retrieval is a <b>tool</b>, so the
 *       model searches filings only when the question calls for it and can scope the search
 *       by company, year and version.
 *   <li>{@code filingsChatClient} — document Q&amp;A only. Retrieval is an <b>advisor</b>, so
 *       every question is answered from the filings with no chance of the model taking a
 *       market-data shortcut.
 * </ul>
 *
 * <p>System prompts live in {@code src/main/resources/prompts/*.st} rather than in this
 * file. Prompts are edited far more often than wiring code, they are reviewed by people who
 * should not have to read Java to do it, and keeping them as resources means a prompt change
 * shows up as a diff to a text file instead of buried in a configuration class.
 */
@Configuration
public class StockAgentConfig {

    private final Resource stockAgentPrompt;
    private final Resource filingsQaPrompt;
    private final Resource guardrailRefusalPrompt;

    public StockAgentConfig(
            @Value("classpath:prompts/stock-agent-system.st") Resource stockAgentPrompt,
            @Value("classpath:prompts/filings-qa-system.st") Resource filingsQaPrompt,
            @Value("classpath:prompts/guardrail-refusal.st") Resource guardrailRefusalPrompt) {

        this.stockAgentPrompt = stockAgentPrompt;
        this.filingsQaPrompt = filingsQaPrompt;
        this.guardrailRefusalPrompt = guardrailRefusalPrompt;
    }

    @Bean
    public GuardrailAdvisor guardrailAdvisor(GuardrailService guardrailService, MeterRegistry meterRegistry) {
        return new GuardrailAdvisor(guardrailService, readText(guardrailRefusalPrompt), meterRegistry);
    }

    /**
     * Conversation memory, persisted to Postgres.
     *
     * <p>A sliding window rather than the whole transcript: history is replayed into the
     * prompt on every turn, so unbounded history means cost and latency growing without
     * limit until the context window is exceeded outright.
     *
     * <p>{@code JdbcChatMemoryRepository} is auto-configured from the shared
     * {@code DataSource}; its own schema initialiser is switched off because Liquibase
     * creates {@code SPRING_AI_CHAT_MEMORY} in changeset 005.
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, ChatMemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.maxMessages())
                .build();
    }

    /**
     * Replays prior turns into the prompt and persists each new one.
     *
     * <p>Ordering against the guardrail is deliberate and load-bearing — see
     * {@link #advisors}.
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * The main agent: OpenAI chat model with live market tools and filing retrieval tools.
     *
     * <p>Bound to {@code openAiChatModel} rather than the generic {@code ChatModel} bean
     * because tool calling in a multi-step loop is where hosted models still meaningfully
     * outperform small local ones.
     */
    @Bean
    public ChatClient stockAgentChatClient(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            StockTools stockTools,
            FilingTools filingTools,
            GuardrailAdvisor guardrailAdvisor,
            GuardrailProperties guardrailProperties,
            MessageChatMemoryAdvisor memoryAdvisor,
            ChatMemoryProperties memoryProperties) {

        return ChatClient.builder(chatModel)
                .defaultSystem(stockAgentPrompt)
                .defaultTools(stockTools, filingTools)
                .defaultAdvisors(advisors(
                        guardrailAdvisor, guardrailProperties, memoryAdvisor, memoryProperties))
                .build();
    }

    /**
     * Document-only Q&amp;A over the filing corpus.
     *
     * <p>{@link QuestionAnswerAdvisor} retrieves on every request and appends the results to
     * the prompt automatically — the right trade-off here, where every question is by
     * definition a document question.
     *
     * <p>Note this path searches <i>all</i> versions: it has no tool parameters through which
     * to express a version scope. Use {@code /api/agent/ask} when latest-only matters.
     */
    @Bean
    public ChatClient filingsChatClient(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            VectorStore vectorStore,
            RagProperties ragProperties,
            GuardrailAdvisor guardrailAdvisor,
            GuardrailProperties guardrailProperties,
            MessageChatMemoryAdvisor memoryAdvisor,
            ChatMemoryProperties memoryProperties) {

        QuestionAnswerAdvisor retrievalAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(ragProperties.topK())
                        .similarityThreshold(ragProperties.similarityThreshold())
                        .build())
                .build();

        List<Advisor> advisors = advisors(
                guardrailAdvisor, guardrailProperties, memoryAdvisor, memoryProperties);
        advisors.add(retrievalAdvisor);

        return ChatClient.builder(chatModel)
                .defaultSystem(filingsQaPrompt)
                .defaultAdvisors(advisors)
                .build();
    }

    /**
     * Builds the advisor chain in the order that matters.
     *
     * <ol>
     *   <li><b>Guardrail</b> (highest precedence). A refused question must never reach the
     *       memory advisor: writing it to history would mean the refusal and the offending
     *       text are replayed into the prompt on every subsequent turn of that conversation,
     *       which both wastes context and hands an attacker persistence. It also must not
     *       reach retrieval or the model, so a refusal costs nothing.
     *   <li><b>Memory</b>. Replays the window and persists the new exchange.
     *   <li><b>Retrieval</b> (document-only client). After memory, so the retrieved passages
     *       sit closest to the question rather than being buried under replayed history.
     *   <li><b>Logging</b> last, to record what was actually sent.
     * </ol>
     */
    private static List<Advisor> advisors(
            GuardrailAdvisor guardrailAdvisor,
            GuardrailProperties guardrailProperties,
            MessageChatMemoryAdvisor memoryAdvisor,
            ChatMemoryProperties memoryProperties) {

        List<Advisor> advisors = new ArrayList<>();
        if (guardrailProperties.enabled()) {
            advisors.add(guardrailAdvisor);
        }
        if (memoryProperties.enabled()) {
            advisors.add(memoryAdvisor);
        }
        advisors.add(new SimpleLoggerAdvisor());
        return advisors;
    }

    private static String readText(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            // A missing prompt file is a packaging error; failing at startup is correct.
            throw new UncheckedIOException(
                    "Could not read prompt resource: " + resource.getDescription(), ex);
        }
    }
}
