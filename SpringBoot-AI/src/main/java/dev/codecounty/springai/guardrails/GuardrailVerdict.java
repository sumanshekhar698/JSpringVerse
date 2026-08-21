package dev.codecounty.springai.guardrails;

/**
 * Outcome of a guardrail check.
 *
 * @param allowed  whether the request may proceed to the model
 * @param category machine-readable reason, used as a metric tag
 * @param reason   human-readable explanation, logged but never returned verbatim to the
 *                 caller — telling a prospective attacker exactly which pattern tripped
 *                 makes the next attempt easier
 */
public record GuardrailVerdict(boolean allowed, Category category, String reason) {

    public enum Category {
        /** Passed every check. */
        ALLOWED,
        /** Empty or whitespace-only input. */
        EMPTY_INPUT,
        /** Exceeds the configured length ceiling. */
        INPUT_TOO_LONG,
        /** Attempts to override, reveal or replace the system prompt. */
        PROMPT_INJECTION,
        /** Solicits a buy/sell/hold recommendation or a price prediction. */
        INVESTMENT_ADVICE
    }

    public static final GuardrailVerdict ALLOWED =
            new GuardrailVerdict(true, Category.ALLOWED, "ok");

    public static GuardrailVerdict block(Category category, String reason) {
        return new GuardrailVerdict(false, category, reason);
    }

    public boolean blocked() {
        return !allowed;
    }
}
