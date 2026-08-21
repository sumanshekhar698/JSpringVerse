package dev.codecounty.springai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conversation id handling.
 *
 * <p>The id is the only thing separating one caller's history from another's, and it arrives
 * from the request body, so its validation is worth pinning down independently of the
 * database.
 */
class ConversationServiceTest {

    private final ConversationService service =
            new ConversationService(new NoOpChatMemory(), new NoOpRepository(), ChatMemoryProperties.defaults());

    @Test
    @DisplayName("mints a fresh UUID when no id is supplied")
    void generatesWhenAbsent() {
        String generated = service.resolveConversationId(null);

        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        // Two callers starting fresh must not collide into one shared transcript.
        assertThat(service.resolveConversationId("")).isNotEqualTo(generated);
        assertThat(service.resolveConversationId("   ")).isNotEqualTo(generated);
    }

    @Test
    @DisplayName("accepts and normalises a well-formed id")
    void acceptsValidId() {
        assertThat(service.resolveConversationId("session-abc-123")).isEqualTo("session-abc-123");
        assertThat(service.resolveConversationId("  session_ABC_123  ")).isEqualTo("session_abc_123");
        assertThat(service.resolveConversationId(UUID.randomUUID().toString())).hasSize(36);
    }

    @ParameterizedTest
    @DisplayName("rejects ids that are malformed, oversized, or carry injection payloads")
    @ValueSource(strings = {
            "short",                                    // under 8 chars
            "id with spaces",
            "id;DROP TABLE SPRING_AI_CHAT_MEMORY",
            "../../etc/passwd",
            "conv\nid",                                 // newline: log injection
            "abcdefgh12345678901234567890123456789012"  // over the VARCHAR(36) column width
    })
    void rejectsMalformedIds(String candidate) {
        assertThatThrownBy(() -> service.resolveConversationId(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }

    // --- Stubs ---------------------------------------------------------------------------

    private static final class NoOpChatMemory implements ChatMemory {
        @Override
        public void add(String conversationId, List<Message> messages) {
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }
    }

    private static final class NoOpRepository implements ChatMemoryRepository {
        @Override
        public List<String> findConversationIds() {
            return List.of();
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
            return List.of();
        }

        @Override
        public void saveAll(String conversationId, List<Message> messages) {
        }

        @Override
        public void deleteByConversationId(String conversationId) {
        }
    }
}
