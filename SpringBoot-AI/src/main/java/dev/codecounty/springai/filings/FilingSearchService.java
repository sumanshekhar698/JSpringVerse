package dev.codecounty.springai.filings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic search over the ingested filing corpus, with metadata and version scoping.
 *
 * <p>Two failure modes are designed out here. <b>Cross-company bleed</b>: pure similarity
 * search returns risk-factor text from three issuers at once, so every query can be narrowed
 * by ticker and form type. <b>Cross-version bleed</b>: successive editions of a filing
 * contradict each other by design, so {@link VersionScope#LATEST} is the default and history
 * is opt-in.
 *
 * <p>Version scoping resolves to a {@code document_id IN (...)} filter rather than a stored
 * "is latest" flag on each chunk. A flag would have to be rewritten across every chunk of
 * the previous edition each time a new filing arrives — a multi-hundred-row update that is
 * easy to get half-done. Resolving at query time cannot drift.
 */
@Service
public class FilingSearchService {

    private static final Logger log = LoggerFactory.getLogger(FilingSearchService.class);

    private final VectorStore vectorStore;
    private final FilingRegistry registry;
    private final RagProperties ragProperties;
    private final String embeddingModel;

    public FilingSearchService(
            VectorStore vectorStore,
            FilingRegistry registry,
            RagProperties ragProperties,
            @Value("${spring.ai.model.embedding:openai}") String embeddingModel) {

        this.vectorStore = vectorStore;
        this.registry = registry;
        this.ragProperties = ragProperties;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Optional narrowing criteria.
     *
     * @param versionScope never null; defaults to {@link VersionScope#LATEST}
     */
    public record FilingFilter(
            String ticker,
            FilingType formType,
            Integer fiscalYear,
            VersionScope versionScope) {

        public FilingFilter {
            ticker = (ticker == null || ticker.isBlank()) ? null : ticker.trim().toUpperCase(Locale.ROOT);
            // An explicit fiscal year is itself a version choice; honouring LATEST on top of
            // it would usually yield nothing, since the latest filing is rarely the one the
            // user named.
            versionScope = (versionScope == null || fiscalYear != null) ? VersionScope.ALL : versionScope;
        }

        public static FilingFilter latest() {
            return new FilingFilter(null, null, null, VersionScope.LATEST);
        }
    }

    /** What a search actually ran, so the caller can tell the user which versions were used. */
    public record SearchOutcome(List<Document> hits, List<FilingDocument> documentsSearched, VersionScope scope) {
    }

    /**
     * Retrieves the chunks most relevant to a question.
     *
     * @param topK number of chunks to return; {@code null} uses the configured default
     */
    public SearchOutcome search(String query, FilingFilter filter, Integer topK) {
        if (query == null || query.isBlank()) {
            return new SearchOutcome(List.of(), List.of(), VersionScope.LATEST);
        }
        FilingFilter effective = filter == null ? FilingFilter.latest() : filter;

        List<FilingDocument> scoped = List.of();
        if (effective.versionScope() == VersionScope.LATEST) {
            scoped = registry.findLatest(embeddingModel, effective.ticker(), effective.formType());
            if (scoped.isEmpty()) {
                // Nothing ingested for this scope. Returning empty is correct; falling back
                // to an unscoped search would answer from another company's filing.
                log.debug("No filings registered for ticker={} form={}",
                        effective.ticker(), effective.formType());
                return new SearchOutcome(List.of(), List.of(), effective.versionScope());
            }
        }

        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(topK == null ? ragProperties.topK() : Math.clamp(topK, 1, 50))
                .similarityThreshold(ragProperties.similarityThreshold());

        Filter.Expression expression = toExpression(effective, scoped);
        if (expression != null) {
            request.filterExpression(expression);
        }

        List<Document> results = vectorStore.similaritySearch(request.build());
        List<Document> hits = results == null ? List.of() : results;

        log.debug("Filing search '{}' (filter={}) returned {} chunks", query, effective, hits.size());
        return new SearchOutcome(hits, scoped, effective.versionScope());
    }

    /** Every filing on record, newest first, for reporting what is available. */
    public List<FilingDocument> registeredFilings() {
        return registry.findAll(embeddingModel);
    }

    /** The current edition of each (ticker, form type) — everything else is historical. */
    public List<FilingDocument> latestFilings() {
        return registry.findLatest(embeddingModel, null, null);
    }

    /** Fiscal years available for a company, newest first. */
    public List<Integer> availableYears(String ticker, FilingType formType) {
        return registry.findFiscalYears(embeddingModel,
                ticker == null ? null : ticker.toUpperCase(Locale.ROOT), formType);
    }

    private static Filter.Expression toExpression(FilingFilter filter, List<FilingDocument> scopedDocuments) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> predicates = new ArrayList<>(4);

        if (!scopedDocuments.isEmpty()) {
            // One IN over document ids covers "latest per company" across every ticker at
            // once, which an OR chain of (ticker AND year) pairs would not do cleanly.
            predicates.add(builder.in(FilingMetadata.KEY_DOCUMENT_ID,
                    scopedDocuments.stream().map(document -> (Object) document.id().toString()).toList()));
        }
        else {
            if (filter.ticker() != null) {
                predicates.add(builder.eq(FilingMetadata.KEY_TICKER, filter.ticker()));
            }
            if (filter.formType() != null) {
                predicates.add(builder.eq(FilingMetadata.KEY_FORM_TYPE, filter.formType().label()));
            }
        }
        if (filter.fiscalYear() != null) {
            predicates.add(builder.eq(FilingMetadata.KEY_FISCAL_YEAR, filter.fiscalYear()));
        }

        if (predicates.isEmpty()) {
            return null;
        }
        FilterExpressionBuilder.Op combined = predicates.getFirst();
        for (int i = 1; i < predicates.size(); i++) {
            combined = builder.and(combined, predicates.get(i));
        }
        return combined.build();
    }
}
