package dev.codecounty.springai.memory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conversation memory tuning, prefixed {@code jspringverse.memory.*}.
 */
@Validated
@ConfigurationProperties(prefix = "jspringverse.memory")
public record ChatMemoryProperties(

        /** Turn conversation memory off entirely; every request becomes independent. */
        boolean enabled,

        /**
         * Messages retained per conversation before the oldest are dropped.
         *
         * <p>A window rather than the full history, because the transcript is replayed into
         * the prompt on every turn — unbounded history means cost and latency that grow
         * without limit until the context window is exceeded outright.
         *
         * <p>Counted in messages, not turns, and this agent's turns are message-heavy: one
         * question that resolves a ticker then fetches a quote produces a user message, two
         * tool-call messages, two tool-result messages and an answer. 40 is roughly a dozen
         * such exchanges.
         */
        @Min(2) @Max(500) int maxMessages) {

    public ChatMemoryProperties {
        maxMessages = maxMessages <= 0 ? 40 : maxMessages;
    }

    /** Shipped defaults, for tests and reference. */
    public static ChatMemoryProperties defaults() {
        return new ChatMemoryProperties(true, 40);
    }
}
