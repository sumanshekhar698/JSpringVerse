package dev.codecounty.springai.tools;

import dev.codecounty.springai.filings.FilingDocument;
import dev.codecounty.springai.filings.FilingMetadata;
import dev.codecounty.springai.filings.FilingSearchService;
import dev.codecounty.springai.filings.FilingType;
import dev.codecounty.springai.filings.VersionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * Retrieval over ingested SEC filings, exposed to the agent as tools.
 *
 * <p>Retrieval is a tool rather than a
 * {@link org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor}
 * on the main agent for three reasons. First, this agent mixes live market data with
 * document lookup — an advisor would run a vector search on every turn, including "what's
 * the price of AAPL", paying for an embedding call and injecting irrelevant filing text.
 * Second, an advisor cannot express <i>which</i> company and year to scope to. Third, and
 * most important here, it cannot express <i>which version</i>: the model needs to choose
 * between current disclosure and historical comparison, and that has to be a parameter.
 *
 * <p>The document-only endpoint still uses the advisor — see {@code filingsChatClient}.
 */
@Component
public class FilingTools {

    private static final Logger log = LoggerFactory.getLogger(FilingTools.class);

    /** Characters of chunk text shown per hit. Bounds prompt growth on multi-hit searches. */
    private static final int MAX_EXCERPT_CHARS = 1200;

    private final FilingSearchService searchService;

    public FilingTools(FilingSearchService searchService) {
        this.searchService = searchService;
    }

    @Tool(description = """
            Search the company's uploaded SEC filings (10-K annual reports, 10-Q quarterly
            reports) for passages relevant to a question. Use this for anything about a
            company's business, risk factors, strategy, segments, litigation, accounting
            policies, or reported financials — that information lives in the filings, not in
            market data.

            By default this searches only the MOST RECENT filing on record for each company,
            which is what you want for "what does the company say about X". To compare across
            years or answer a question about a past period, pass versionScope=ALL or an
            explicit fiscalYear.

            Narrow with ticker and formType whenever the user implies them. Every result
            carries a citation you must quote in your answer.""")
    public String searchFilings(
            @ToolParam(description = "What to look for, phrased as a question or topic, e.g. 'supply chain concentration risk'")
            String query,
            @ToolParam(required = false, description = "Restrict to one company's filings by ticker, e.g. AAPL. Omit to search all.")
            String ticker,
            @ToolParam(required = false, description = "Restrict to a form type: '10-K' or '10-Q'. Omit to search both.")
            String formType,
            @ToolParam(required = false, description = "Restrict to one fiscal year, e.g. 2023. Implies searching historical versions.")
            Integer fiscalYear,
            @ToolParam(required = false, description = "'LATEST' (default) searches only the newest filing per company; 'ALL' searches every version ever ingested.")
            String versionScope) {

        log.debug("Tool searchFilings(query={}, ticker={}, form={}, year={}, scope={})",
                query, ticker, formType, fiscalYear, versionScope);

        FilingSearchService.FilingFilter filter = new FilingSearchService.FilingFilter(
                ticker,
                (formType == null || formType.isBlank()) ? null : FilingType.from(formType),
                fiscalYear,
                VersionScope.from(versionScope));

        FilingSearchService.SearchOutcome outcome = searchService.search(query, filter, null);

        if (outcome.hits().isEmpty()) {
            return noResultsGuidance(filter, outcome);
        }

        StringJoiner out = new StringJoiner("\n\n");
        out.add("Found %d relevant passage(s)%s. %s".formatted(
                outcome.hits().size(), describeFilter(filter), describeScope(outcome)));

        int index = 1;
        for (Document hit : outcome.hits()) {
            out.add("[%d] %s\n%s".formatted(index++, citationOf(hit), excerpt(hit.getText())));
        }
        return out.toString();
    }

    @Tool(description = """
            List which SEC filings have been uploaded and indexed — company, form type, fiscal
            year, and which edition is the current one. Call this when the user asks what
            documents are available, when you need to know which years can be compared, or
            when a filing search returns nothing and you need to check whether the document
            was ever ingested.""")
    public String listAvailableFilings() {
        log.debug("Tool listAvailableFilings()");

        List<FilingDocument> filings = searchService.registeredFilings();
        if (filings.isEmpty()) {
            return "No filings have been ingested yet. "
                    + "Documents must be uploaded via POST /api/filings before they can be searched.";
        }

        List<FilingDocument> latest = searchService.latestFilings();

        StringJoiner out = new StringJoiner("\n");
        out.add("Indexed filings (%d). 'current' marks the newest edition of each form per company:"
                .formatted(filings.size()));

        for (FilingDocument filing : filings) {
            out.add("- %s%s — %s, %d chunks, ingested %s%s".formatted(
                    filing.citation(),
                    isCurrent(filing, latest) ? " [current]" : " [historical]",
                    filing.companyName() == null ? "unknown company" : filing.companyName(),
                    filing.chunkCount() == null ? 0 : filing.chunkCount(),
                    filing.ingestedAt(),
                    filing.periodEndDate() == null ? "" : ", period ending " + filing.periodEndDate()));
        }
        out.add("");
        out.add("Pass versionScope=ALL or a fiscalYear to searchFilings to reach the historical ones.");
        return out.toString();
    }

    /**
     * Tells the model what to do next instead of just reporting emptiness — otherwise it
     * tends to fall back on general knowledge and present it as if it came from the filing.
     */
    private static String noResultsGuidance(
            FilingSearchService.FilingFilter filter, FilingSearchService.SearchOutcome outcome) {

        StringBuilder guidance = new StringBuilder(
                "No passages matched that query%s.".formatted(describeFilter(filter)));

        if (filter.versionScope() == VersionScope.LATEST && !outcome.documentsSearched().isEmpty()) {
            guidance.append(" Only the current edition was searched (")
                    .append(outcome.documentsSearched().stream()
                            .map(FilingDocument::citation).toList())
                    .append("). If the user is asking about an earlier period, retry with versionScope=ALL.");
        }
        else if (outcome.documentsSearched().isEmpty() && filter.versionScope() == VersionScope.LATEST) {
            guidance.append(" No filings are registered for that company or form type — "
                    + "call listAvailableFilings to see what has been ingested.");
        }

        guidance.append(" Do not answer from general knowledge as if it came from the filing; "
                + "tell the user the filing does not cover this.");
        return guidance.toString();
    }

    private static boolean isCurrent(FilingDocument candidate, List<FilingDocument> latest) {
        return latest.stream().anyMatch(document -> document.id().equals(candidate.id()));
    }

    /** Builds the citation the model is instructed to reproduce, e.g. {@code AAPL 10-K FY2024, Item 1A}. */
    private static String citationOf(Document document) {
        var metadata = document.getMetadata();
        StringBuilder citation = new StringBuilder();
        citation.append(string(metadata.get(FilingMetadata.KEY_TICKER), "Unknown issuer"));
        citation.append(' ').append(string(metadata.get(FilingMetadata.KEY_FORM_TYPE), ""));

        Object year = metadata.get(FilingMetadata.KEY_FISCAL_YEAR);
        if (year != null) {
            citation.append(" FY").append(year);
        }
        Object period = metadata.get(FilingMetadata.KEY_FISCAL_PERIOD);
        if (period != null) {
            citation.append(' ').append(period);
        }
        Object section = metadata.get(FilingMetadata.KEY_SECTION);
        if (section != null) {
            citation.append(", ").append(section);
        }
        if (document.getScore() != null) {
            citation.append(" (similarity %.2f)".formatted(document.getScore()));
        }
        return citation.toString().trim();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        return normalized.length() <= MAX_EXCERPT_CHARS
                ? normalized
                : normalized.substring(0, MAX_EXCERPT_CHARS) + "… [truncated]";
    }

    private static String describeScope(FilingSearchService.SearchOutcome outcome) {
        if (outcome.scope() == VersionScope.ALL) {
            return "Searched all versions on record, so passages may come from different fiscal years — "
                    + "state which year each one is from.";
        }
        return "Searched the current edition only: "
                + outcome.documentsSearched().stream().map(FilingDocument::citation).toList() + ".";
    }

    private static String describeFilter(FilingSearchService.FilingFilter filter) {
        StringJoiner parts = new StringJoiner(", ");
        if (filter.ticker() != null) {
            parts.add("ticker=" + filter.ticker());
        }
        if (filter.formType() != null) {
            parts.add("form=" + filter.formType().label());
        }
        if (filter.fiscalYear() != null) {
            parts.add("FY" + filter.fiscalYear());
        }
        return parts.length() == 0 ? "" : " [" + parts + "]";
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
