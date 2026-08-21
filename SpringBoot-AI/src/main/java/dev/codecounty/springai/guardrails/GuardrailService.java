package dev.codecounty.springai.guardrails;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Input and output checks applied around every agent call.
 *
 * <p>Deliberately pattern-based rather than model-based. A second LLM call to classify the
 * request would double latency and cost on every turn, and would itself be susceptible to
 * the injection it is meant to catch. These patterns are a cheap first layer; the system
 * prompt is the second, and the tools returning only real data is the third.
 *
 * <p>This is not a complete defence and is not claimed to be. A determined attacker will
 * get past regexes. What it does reliably is stop the common cases — casual advice-seeking
 * and copy-pasted injection strings — from reaching the model at all.
 */
@Service
public class GuardrailService {

    /**
     * Attempts to override, reveal or replace the system instructions.
     *
     * <p>Written against the observed shapes rather than trying to be exhaustive: the point
     * is to catch pasted payloads, not to win an adversarial game with regexes.
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)\\bignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|rules?)"),
            Pattern.compile("(?i)\\bdisregard\\s+(all\\s+|any\\s+)?(previous|prior|your|the)\\s+(instructions?|prompts?|rules?|system)"),
            Pattern.compile("(?i)\\b(reveal|show|print|repeat|output|display)\\s+(me\\s+)?(your|the)\\s+(system\\s+)?(prompt|instructions?|rules?)"),
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\s+(a|an|no longer)\\b"),
            Pattern.compile("(?i)\\bforget\\s+(everything|all|your)\\b.{0,30}\\b(instructions?|rules?|told)"),
            Pattern.compile("(?i)\\b(developer|system|admin)\\s+mode\\b"),
            Pattern.compile("(?i)\\bpretend\\s+(you|to\\s+be)\\b.{0,40}\\b(not|no longer|unrestricted)\\b"),
            Pattern.compile("(?i)<\\s*/?\\s*(system|instructions?)\\s*>"));

    /**
     * Solicitations for a recommendation or a forecast.
     *
     * <p>The agent is grounded in filings and quotes, neither of which supports a
     * recommendation. Refusing at the door is more reliable than hoping the system prompt
     * holds, and gives the user a consistent explanation.
     */
    private static final List<Pattern> ADVICE_PATTERNS = List.of(
            Pattern.compile("(?i)\\bshould\\s+i\\s+(buy|sell|short|hold|invest|purchase|dump|acquire)\\b"),
            Pattern.compile("(?i)\\b(is|are)\\s+(it|this|that|they|\\w+)\\s+a\\s+(good|bad|smart|safe)\\s+(buy|sell|investment|bet|stock)\\b"),
            Pattern.compile("(?i)\\b(what|which)\\s+(stocks?|shares?|tickers?)\\s+should\\s+i\\b"),
            Pattern.compile("(?i)\\bwill\\s+\\S+\\s+(stock\\s+)?(go|move)\\s+(up|down)\\b"),
            Pattern.compile("(?i)\\b(price\\s+target|predict|forecast)\\b.{0,30}\\b(price|stock|share)\\b"),
            Pattern.compile("(?i)\\bhow\\s+much\\s+should\\s+i\\s+(invest|buy|put)\\b"),
            Pattern.compile("(?i)\\b(recommend|advise)\\s+(me\\s+)?(a\\s+)?(stock|share|investment|portfolio)\\b"),
            Pattern.compile("(?i)\\bworth\\s+(buying|investing|shorting)\\b"));

    /** Names a security or market concept, so the disclaimer is relevant. */
    private static final Pattern SECURITIES_CONTEXT = Pattern.compile(
            "(?i)\\b(stock|share|ticker|equity|equities|10-K|10-Q|earnings|revenue|dividend|"
                    + "valuation|market cap|portfolio|NASDAQ|NYSE)\\b");

    private static final String DISCLAIMER =
            "\n\n---\n_Research information only, not investment advice. "
                    + "Figures come from live market data and the company's own SEC filings as cited; "
                    + "verify against the primary source before acting._";

    private final GuardrailProperties properties;

    public GuardrailService(GuardrailProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks a user question before it reaches the model.
     *
     * @return {@link GuardrailVerdict#ALLOWED} or a blocking verdict
     */
    public GuardrailVerdict checkInput(String question) {
        if (!properties.enabled()) {
            return GuardrailVerdict.ALLOWED;
        }

        if (question == null || question.isBlank()) {
            return GuardrailVerdict.block(
                    GuardrailVerdict.Category.EMPTY_INPUT, "question was blank");
        }

        if (question.length() > properties.maxQuestionLength()) {
            return GuardrailVerdict.block(GuardrailVerdict.Category.INPUT_TOO_LONG,
                    "question was %d characters, limit is %d"
                            .formatted(question.length(), properties.maxQuestionLength()));
        }

        if (properties.blockPromptInjection()) {
            for (Pattern pattern : INJECTION_PATTERNS) {
                if (pattern.matcher(question).find()) {
                    return GuardrailVerdict.block(GuardrailVerdict.Category.PROMPT_INJECTION,
                            "matched injection pattern: " + pattern.pattern());
                }
            }
        }

        if (properties.blockInvestmentAdvice()) {
            for (Pattern pattern : ADVICE_PATTERNS) {
                if (pattern.matcher(question).find()) {
                    return GuardrailVerdict.block(GuardrailVerdict.Category.INVESTMENT_ADVICE,
                            "matched advice pattern: " + pattern.pattern());
                }
            }
        }

        return GuardrailVerdict.ALLOWED;
    }

    /**
     * Post-processes a model answer.
     *
     * <p>Only ever appends; it never rewrites or truncates the model's words. A guardrail
     * that silently edits an answer is worse than one that annotates it, because the caller
     * can no longer tell what the model actually said.
     */
    public String applyOutputGuardrails(String answer) {
        if (!properties.enabled() || !properties.appendDisclaimer() || answer == null || answer.isBlank()) {
            return answer;
        }
        if (answer.contains("not investment advice")) {
            return answer;
        }
        return SECURITIES_CONTEXT.matcher(answer).find() ? answer + DISCLAIMER : answer;
    }
}
