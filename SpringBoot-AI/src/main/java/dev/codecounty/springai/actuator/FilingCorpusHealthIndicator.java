package dev.codecounty.springai.actuator;

import dev.codecounty.springai.filings.FilingCatalog;
import dev.codecounty.springai.filings.FilingDocument;
import dev.codecounty.springai.filings.FilingSearchService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reports the state of the RAG corpus.
 *
 * <p>An agent whose vector table is empty or unreachable answers "the filing does not cover
 * this" to every document question — a plausible-sounding response that looks like normal
 * operation. Surfacing chunk counts and the current filing set here turns that silent
 * degradation into something a monitor can alert on.
 *
 * <p>Reports UP with {@code empty: true} rather than DOWN when nothing is ingested: a fresh
 * deployment with no filings yet is correctly configured, not broken.
 */
@Component("filingCorpus")
public class FilingCorpusHealthIndicator implements HealthIndicator {

    private final FilingCatalog filingCatalog;
    private final FilingSearchService searchService;

    public FilingCorpusHealthIndicator(FilingCatalog filingCatalog, FilingSearchService searchService) {
        this.filingCatalog = filingCatalog;
        this.searchService = searchService;
    }

    @Override
    public Health health() {
        try {
            long chunks = filingCatalog.totalChunks();
            List<FilingDocument> all = searchService.registeredFilings();
            List<FilingDocument> latest = searchService.latestFilings();

            return Health.up()
                    .withDetail("vectorTable", filingCatalog.vectorTable())
                    .withDetail("documentsIndexed", all.size())
                    .withDetail("totalChunks", chunks)
                    .withDetail("empty", all.isEmpty())
                    .withDetail("currentFilings", latest.stream().map(FilingDocument::citation).toList())
                    .build();
        }
        catch (Exception ex) {
            // Almost always the database being unreachable or the changelog not applied.
            return Health.down()
                    .withDetail("vectorTable", filingCatalog.vectorTable())
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
