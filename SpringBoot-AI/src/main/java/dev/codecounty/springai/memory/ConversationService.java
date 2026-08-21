package dev.codecounty.springai.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Conversation lifecycle: identifiers, transcript reads, and deletion.
 *
 * <p>Sits between the controllers and Spring AI's {@link ChatMemory} so that identifier
 * validation happens in exactly one place. That matters more than it looks: the
 * conversation id is the <b>only</b> thing separating one user's history from another's, and
 * it is supplied by the caller.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /**
     * Conversation ids are stored in a {@code VARCHAR(36)} column and interpolated into no
     * SQL, but they are still attacker-controlled and end up in logs. Restricting them to a
     * conservative character set keeps them from carrying log-injection payloads or
     * exceeding the column width.
     */
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{8,36}$");

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatMemoryProperties properties;

    public ConversationService(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository,
                               ChatMemoryProperties properties) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.properties = properties;
    }

    /**
     * Validates a caller-supplied id, or mints a fresh one.
     *
     * @param requested id from the request, may be null or blank
     * @return a usable conversation id
     * @throws IllegalArgumentException if a supplied id is malformed
     */
    public String resolveConversationId(String requested) {
        if (requested == null || requested.isBlank()) {
            String generated = UUID.randomUUID().toString();
            log.debug("Started conversation {}", generated);
            return generated;
        }

        String trimmed = requested.trim();
        if (!VALID_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "conversationId must be 8-36 characters of letters, digits, '-' or '_'");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** Full retained transcript for a conversation, oldest first. */
    public List<Message> transcript(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /** Every conversation id on record. */
    public List<String> conversationIds() {
        return chatMemoryRepository.findConversationIds();
    }

    /** Forgets a conversation. Irreversible. */
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
        log.info("Cleared conversation {}", conversationId);
    }
}
