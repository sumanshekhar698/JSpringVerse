package dev.codecounty.springai.filings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Chunking and retrieval tuning, prefixed {@code jspringverse.rag.*}.
 *
 * <p>Defaults are set for SEC filings specifically: long, dense, heavily cross-referenced
 * documents where a chunk needs enough surrounding text to stand alone as an answer.
 */
@Validated
@ConfigurationProperties(prefix = "jspringverse.rag")
public record RagProperties(

        /**
         * Target chunk size in tokens. 800 is a deliberate middle ground for filings: large
         * enough that a risk factor or an MD&A paragraph survives intact, small enough that
         * several chunks fit in the prompt alongside the question.
         */
        @Min(100) @Max(4000) int chunkSizeTokens,

        /** Chunks shorter than this many characters are discarded as noise. */
        @Positive int minChunkLengthToEmbed,

        /** Soft lower bound on chunk length in characters, used to avoid splitting mid-sentence. */
        @Positive int minChunkSizeChars,

        /**
         * Documents sent to the embedding model per request. Keeps each call well inside
         * provider payload limits and bounds memory during a large ingest.
         */
        @Min(1) @Max(1000) int embeddingBatchSize,

        /** Default number of chunks retrieved per query. */
        @Min(1) @Max(50) int topK,

        /**
         * Minimum cosine similarity for a chunk to be used. Above the noise floor so an
         * unrelated question returns nothing rather than the least-bad chunk in the corpus —
         * which is what causes a RAG agent to answer confidently from irrelevant text.
         *
         * <p><b>Calibrated, not guessed.</b> Measured against Apple's FY2025 10-K embedded
         * with {@code text-embedding-3-small}:
         *
         * <pre>
         *   "supply chain risks and supplier concentration"  -> 0.478  (Item 1A)
         *   "revenue by segment and gross margin"            -> 0.462  (Item 8 / Item 7)
         *   "the weather in Paris tomorrow"                  -> 0.143
         *   "how to bake sourdough bread"                    -> 0.072
         * </pre>
         *
         * <p>So the usable band is roughly 0.14 (noise ceiling) to 0.38 (signal floor), and
         * 0.28 sits in the middle of it. The intuition that "relevant means ~0.7+" is wrong
         * for this model: chunk-to-chunk similarity does run 0.85+, but a short query against
         * a long document chunk scores far lower. An earlier value of 0.55 sat above every
         * achievable score and silently returned zero hits for every question.
         *
         * <p>Re-measure after changing embedding model, chunk size, or corpus:
         * {@code GET /api/filings/search} reports the score of every hit.
         */
        Double similarityThreshold) {

    public RagProperties {
        chunkSizeTokens = chunkSizeTokens <= 0 ? 800 : chunkSizeTokens;
        minChunkLengthToEmbed = minChunkLengthToEmbed <= 0 ? 50 : minChunkLengthToEmbed;
        minChunkSizeChars = minChunkSizeChars <= 0 ? 500 : minChunkSizeChars;
        embeddingBatchSize = embeddingBatchSize <= 0 ? 100 : embeddingBatchSize;
        topK = topK <= 0 ? 8 : topK;
        // Boxed and null-checked rather than `<= 0`, because 0 is a legitimate setting
        // meaning "no floor" — and treating it as unset makes the threshold impossible to
        // disable for diagnosis, which is exactly what hid the 0.55 bug.
        similarityThreshold = similarityThreshold == null ? 0.28 : similarityThreshold;
        if (similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException(
                    "jspringverse.rag.similarity-threshold must be between 0 and 1, got "
                            + similarityThreshold);
        }
    }
}
