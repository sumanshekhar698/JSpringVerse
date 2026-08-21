package dev.codecounty.springai.filings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the similarity threshold against the two ways it has already gone wrong.
 *
 * <p>Context: an earlier default of {@code 0.55} sat above every score this embedding model
 * actually produces for a query-to-chunk comparison, so retrieval silently returned zero
 * hits for every question. The agent then correctly refused to answer — which looked like
 * working grounding rather than broken search, and hid the bug.
 *
 * <p>The second failure compounded it: defaulting on {@code <= 0} made {@code 0} impossible
 * to set, so "turn the threshold off and see the raw scores" quietly kept using 0.55.
 */
class RagPropertiesTest {

    /** Highest score observed from a deliberately irrelevant query against a real 10-K. */
    private static final double MEASURED_NOISE_CEILING = 0.143;

    /** Lowest score observed among hits for a clearly relevant query. */
    private static final double MEASURED_SIGNAL_FLOOR = 0.38;

    private static RagProperties withThreshold(Double threshold) {
        return new RagProperties(800, 50, 500, 100, 8, threshold);
    }

    @Test
    @DisplayName("default threshold sits between the measured noise ceiling and signal floor")
    void defaultThresholdIsInTheUsableBand() {
        double threshold = withThreshold(null).similarityThreshold();

        assertThat(threshold)
                .as("must exclude irrelevant chunks (measured up to %.3f)", MEASURED_NOISE_CEILING)
                .isGreaterThan(MEASURED_NOISE_CEILING)
                .as("must admit relevant chunks (measured down to %.3f)", MEASURED_SIGNAL_FLOOR)
                .isLessThan(MEASURED_SIGNAL_FLOOR);
    }

    @Test
    @DisplayName("zero is settable — it means 'no floor', not 'unset'")
    void zeroIsAnExplicitSetting() {
        // The regression that hid the original bug: `<= 0 ? default : value` silently
        // replaced an explicit 0 with the default, so the threshold could not be disabled.
        assertThat(withThreshold(0.0).similarityThreshold()).isZero();
    }

    @Test
    @DisplayName("an explicit threshold is honoured verbatim")
    void honoursExplicitValues() {
        assertThat(withThreshold(0.42).similarityThreshold()).isEqualTo(0.42);
        assertThat(withThreshold(1.0).similarityThreshold()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("out-of-range thresholds fail fast at startup, not at query time")
    void rejectsImpossibleValues() {
        // A cosine similarity above 1 can never match, which would look exactly like the
        // 0.55 bug: no error, no hits, an agent that politely declines every question.
        assertThatThrownBy(() -> withThreshold(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> withThreshold(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    @DisplayName("other properties still fall back when unset")
    void otherDefaultsHold() {
        RagProperties defaults = new RagProperties(0, 0, 0, 0, 0, null);

        assertThat(defaults.chunkSizeTokens()).isEqualTo(800);
        assertThat(defaults.minChunkLengthToEmbed()).isEqualTo(50);
        assertThat(defaults.minChunkSizeChars()).isEqualTo(500);
        assertThat(defaults.embeddingBatchSize()).isEqualTo(100);
        assertThat(defaults.topK()).isEqualTo(8);
    }
}
