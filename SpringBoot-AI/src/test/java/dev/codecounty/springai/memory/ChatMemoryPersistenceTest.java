package dev.codecounty.springai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies conversation memory against the real {@code SPRING_AI_CHAT_MEMORY} table.
 *
 * <p>The point is to prove two things a unit test cannot: that Liquibase's changeset 005
 * produces a schema {@code JdbcChatMemoryRepository} can actually read and write — a column
 * name or CHECK-constraint mismatch would only surface at runtime — and that the sliding
 * window truly evicts, rather than growing the prompt unboundedly until the context window
 * is exceeded in production.
 *
 * <pre>
 *   $env:POSTGRES_TEST="true"; $env:POSTGRES_PASSWORD="..."
 *   .\mvnw.cmd test -Dtest=ChatMemoryPersistenceTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST", matches = "true")
class ChatMemoryPersistenceTest {

    private static final String URL = System.getenv().getOrDefault(
            "POSTGRES_URL", "jdbc:postgresql://localhost:5433/jspringverse");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    private JdbcChatMemoryRepository repository;
    private JdbcTemplate jdbcTemplate;
    private String conversationId;

    @BeforeEach
    void setUp() throws SQLException {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), URL, USER, PASSWORD);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.repository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new PostgresChatMemoryRepositoryDialect())
                .build();
        // Unique per test so runs never interfere, and no cleanup of other rows is needed.
        // Exactly 36 chars — the column is VARCHAR(36), which is a UUID and nothing more.
        this.conversationId = UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("round-trips a conversation through the Liquibase-created table")
    void roundTripsThroughPostgres() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(40)
                .build();

        memory.add(conversationId, new UserMessage("What is AAPL trading at?"));
        memory.add(conversationId, new AssistantMessage("AAPL last traded at 336.91 USD."));
        memory.add(conversationId, new UserMessage("And what about their risk factors?"));

        List<Message> transcript = memory.get(conversationId);

        assertThat(transcript).hasSize(3);
        assertThat(transcript.get(0).getText()).isEqualTo("What is AAPL trading at?");
        assertThat(transcript.get(2).getText()).isEqualTo("And what about their risk factors?");

        // The follow-up is only answerable because the prior turns are replayed — that is
        // the whole feature.
        assertThat(transcript).extracting(message -> message.getMessageType().getValue())
                .containsExactly("user", "assistant", "user");

        memory.clear(conversationId);
        assertThat(memory.get(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("survives a new repository instance — this is what in-memory could not do")
    void survivesRepositoryRecreation() throws SQLException {
        ChatMemory first = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository).maxMessages(40).build();
        first.add(conversationId, new UserMessage("Remember this across a restart"));

        // A second repository over a fresh connection stands in for an application restart
        // or a second instance behind a load balancer.
        SimpleDriverDataSource other = new SimpleDriverDataSource(
                new org.postgresql.Driver(), URL, USER, PASSWORD);
        ChatMemory second = MessageWindowChatMemory.builder()
                .chatMemoryRepository(JdbcChatMemoryRepository.builder()
                        .jdbcTemplate(new JdbcTemplate(other))
                        .dialect(new PostgresChatMemoryRepositoryDialect())
                        .build())
                .maxMessages(40).build();

        assertThat(second.get(conversationId))
                .extracting(Message::getText)
                .containsExactly("Remember this across a restart");

        second.clear(conversationId);
    }

    @Test
    @DisplayName("the window evicts oldest messages instead of growing without bound")
    void windowEvictsOldest() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(4)
                .build();

        for (int i = 1; i <= 10; i++) {
            memory.add(conversationId, new UserMessage("message " + i));
        }

        List<Message> retained = memory.get(conversationId);

        assertThat(retained).hasSize(4);
        assertThat(retained).extracting(Message::getText)
                .containsExactly("message 7", "message 8", "message 9", "message 10");

        // Eviction must reach the table, not just the returned view — otherwise rows
        // accumulate forever and the "window" is a lie at the storage layer.
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(rows).isEqualTo(4);

        memory.clear(conversationId);
    }

    @Test
    @DisplayName("conversations are isolated from each other")
    void isolatesConversations() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository).maxMessages(40).build();
        String other = UUID.randomUUID().toString();

        memory.add(conversationId, new UserMessage("conversation A"));
        memory.add(other, new UserMessage("conversation B"));

        assertThat(memory.get(conversationId)).extracting(Message::getText).containsExactly("conversation A");
        assertThat(memory.get(other)).extracting(Message::getText).containsExactly("conversation B");

        // Clearing one must not touch the other.
        memory.clear(conversationId);
        assertThat(memory.get(other)).hasSize(1);

        memory.clear(other);
    }

    @Test
    @DisplayName("findConversationIds surfaces stored conversations")
    void listsConversationIds() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository).maxMessages(40).build();
        memory.add(conversationId, new UserMessage("hello"));

        assertThat(repository.findConversationIds()).contains(conversationId);

        memory.clear(conversationId);
        assertThat(repository.findConversationIds()).doesNotContain(conversationId);
    }
}
