package dev.codecounty.springai.guardrails;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Guardrail tuning, prefixed {@code jspringverse.guardrails.*}.
 */
@Validated
@ConfigurationProperties(prefix = "jspringverse.guardrails")
public record GuardrailProperties(

        /** Master switch. Disabling it removes the advisor from every chat client. */
        boolean enabled,

        /**
         * Reject questions longer than this many characters before they reach the model.
         * Long inputs are the usual carrier for instruction-injection payloads, and they
         * cost tokens on a request that is about to be refused anyway.
         */
        int maxQuestionLength,

        /** Refuse requests for buy/sell/hold recommendations or price predictions. */
        boolean blockInvestmentAdvice,

        /** Refuse inputs that try to override the system prompt. */
        boolean blockPromptInjection,

        /** Append a "not investment advice" notice to every answer that names a security. */
        boolean appendDisclaimer) {

    public GuardrailProperties {
        maxQuestionLength = maxQuestionLength <= 0 ? 4000 : maxQuestionLength;
    }

    /**
     * Everything on. Used by tests and as a documented reference for the shipped defaults.
     *
     * <p>A static factory rather than a second constructor: constructor binding for a record
     * requires exactly one candidate, and adding a no-arg overload makes the context fail to
     * start with an ambiguity error.
     */
    public static GuardrailProperties defaults() {
        return new GuardrailProperties(true, 4000, true, true, true);
    }
}
