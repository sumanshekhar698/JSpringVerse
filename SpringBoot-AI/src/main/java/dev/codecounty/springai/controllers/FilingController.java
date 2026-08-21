package dev.codecounty.springai.controllers;

import dev.codecounty.springai.filings.FilingCatalog;
import dev.codecounty.springai.filings.FilingDocument;
import dev.codecounty.springai.filings.FilingIngestionException;
import dev.codecounty.springai.filings.FilingIngestionResult;
import dev.codecounty.springai.filings.FilingIngestionService;
import dev.codecounty.springai.filings.FilingMetadata;
import dev.codecounty.springai.filings.FilingMetadataResolver;
import dev.codecounty.springai.filings.FilingSearchService;
import dev.codecounty.springai.filings.FilingType;
import dev.codecounty.springai.filings.VersionScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingestion, inspection and removal of the SEC filings backing the agent's RAG corpus.
 *
 * <p>Two ways in, both REST:
 * <ul>
 *   <li>{@code POST /api/filings} — multipart upload of a local file.
 *   <li>{@code POST /api/filings/from-url} — server-side fetch, for EDGAR links and
 *       automated pipelines that already hold a URL rather than bytes.
 * </ul>
 *
 * <p>Both accept metadata implicitly from the filename ({@code Apple_10K-2025.pdf}) and
 * explicitly from request parameters, with explicit values taking precedence.
 */
@RestController
@RequestMapping("/api/filings")
@Tag(name = "Filings")
public class FilingController {

    private static final Logger log = LoggerFactory.getLogger(FilingController.class);

    /**
     * Ceiling on a server-side fetch. Independent of the multipart limit because the caller
     * controls the URL, not the payload — an unbounded fetch is a memory exhaustion vector.
     */
    private static final long MAX_FETCH_BYTES = 128L * 1024 * 1024;

    private final FilingIngestionService ingestionService;
    private final FilingMetadataResolver metadataResolver;
    private final FilingSearchService searchService;
    private final FilingCatalog filingCatalog;
    private final ChatClient filingsChatClient;
    private final RestClient downloadClient = RestClient.create();

    public FilingController(
            FilingIngestionService ingestionService,
            FilingMetadataResolver metadataResolver,
            FilingSearchService searchService,
            FilingCatalog filingCatalog,
            @Qualifier("filingsChatClient") ChatClient filingsChatClient) {

        this.ingestionService = ingestionService;
        this.metadataResolver = metadataResolver;
        this.searchService = searchService;
        this.filingCatalog = filingCatalog;
        this.filingsChatClient = filingsChatClient;
    }

    /**
     * Upload and index a filing.
     *
     * <p>Metadata is derived from the filename where it follows the
     * {@code <Company>_<Form>-<Period>} convention, and any explicit parameter overrides it.
     *
     * <pre>
     * curl -F file=@Apple_10K-2025.pdf http://localhost:8080/api/filings
     * curl -F file=@scan.pdf -F ticker=AAPL -F formType=10-K -F fiscalYear=2025 \
     *      http://localhost:8080/api/filings
     * </pre>
     */
    @Operation(
            summary = "Upload and index a filing",
            description = """
                    Accepts PDF, HTML (EDGAR) or DOCX.
                    
                    **Metadata comes from the filename** when it follows \
                    `<Company>_<FormType>-<Period>.<ext>` — for example `Apple_10K-2025.pdf`, \
                    `Apple_10Q-2025-Q3.pdf`, `Microsoft_10K-2024-06-30.pdf`. Any parameter you \
                    supply explicitly overrides what the filename says. The ticker is resolved \
                    from the company name via symbol search when not given.
                    
                    **Deduplicated by SHA-256 of the raw bytes.** Identical content returns \
                    `DUPLICATE_SKIPPED` with nothing re-parsed or re-embedded. Use `force=true` \
                    to replace. Note this rejects duplicate *content*, not new *versions* — \
                    uploading the FY2026 10-K alongside FY2025 keeps both.
                    
                    Check `sections` in the response to spot a badly extracted PDF: a scanned \
                    filing yields one `Full document` section or none.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ingested, re-ingested, or skipped as a duplicate — see `status`."),
            @ApiResponse(responseCode = "413", description = "File exceeds the multipart limit.",
                    content = @Content(mediaType = "application/problem+json")),
            @ApiResponse(responseCode = "422",
                    description = "Unparseable, image-only/scanned, or no ticker could be determined.",
                    content = @Content(mediaType = "application/problem+json"))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FilingIngestionResult upload(
            @Parameter(description = "The filing. PDF, HTML or DOCX.")
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "Ticker, e.g. AAPL. Overrides filename; resolved from company name if omitted.")
            @RequestParam(value = "ticker", required = false) String ticker,

            @Parameter(description = "Company name, e.g. 'Apple Inc.'")
            @RequestParam(value = "companyName", required = false) String companyName,

            @Parameter(description = "Form type.", schema = @Schema(allowableValues = {"10-K", "10-Q", "8-K", "DEF 14A"}))
            @RequestParam(value = "formType", required = false) String formType,

            @Parameter(description = "Fiscal year, e.g. 2025. Drives version ordering — a wrong value corrupts version comparison.")
            @RequestParam(value = "fiscalYear", required = false) Integer fiscalYear,

            @Parameter(description = "Fiscal quarter for a 10-Q.", schema = @Schema(allowableValues = {"Q1", "Q2", "Q3", "Q4"}))
            @RequestParam(value = "fiscalPeriod", required = false) String fiscalPeriod,

            @Parameter(description = "Period end, ISO date. Preferred signal for which filing is newest.", example = "2025-09-27")
            @RequestParam(value = "periodEndDate", required = false) String periodEndDate,

            @Parameter(description = "Filing date, ISO date.", example = "2025-11-01")
            @RequestParam(value = "filingDate", required = false) String filingDate,

            @Parameter(description = "Replace an existing copy with the same content hash instead of skipping.")
            @RequestParam(value = "force", defaultValue = "false") boolean force) {

        if (file.isEmpty()) {
            throw new FilingIngestionException("Uploaded file is empty");
        }

        String filename = file.getOriginalFilename();
        FilingMetadata metadata = metadataResolver.resolve(filename, new FilingMetadataResolver.Overrides(
                ticker, companyName, formType, fiscalYear, fiscalPeriod,
                parseDate(periodEndDate, "periodEndDate"), parseDate(filingDate, "filingDate")));

        log.info("Filing upload: {} ({} bytes, force={})", metadata.citation(), file.getSize(), force);
        return ingestionService.ingest(toResource(file), metadata, force);
    }

    public record UrlIngestRequest(
            @NotBlank(message = "url must not be blank") String url,
            String filename,
            String ticker,
            String companyName,
            String formType,
            Integer fiscalYear,
            String fiscalPeriod,
            String periodEndDate,
            String filingDate,
            boolean force) {
    }

    /**
     * Fetch a filing from a URL and index it. Intended for EDGAR document links.
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/filings/from-url \
     *   -H "Content-Type: application/json" \
     *   -d '{"url":"https://www.sec.gov/Archives/.../aapl-10k.htm",
     *        "filename":"Apple_10K-2025.htm"}'
     * </pre>
     *
     * <p>{@code filename} is optional; when omitted it is taken from the URL path, which is
     * what supplies the company/form/period metadata under the naming convention.
     */
    @Operation(
            summary = "Fetch a filing from a URL and index it",
            description = """
                    Intended for SEC EDGAR document links. Only `http`/`https` are accepted and \
                    fetches are capped at 128 MB.
                    
                    `filename` is optional — when omitted it is taken from the URL path, which is \
                    what supplies company/form/period metadata under the naming convention. Supply \
                    it explicitly when the URL ends in something like `aapl-20250927.htm`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Fetched and ingested, or skipped as a duplicate."),
            @ApiResponse(responseCode = "422",
                    description = "Non-http(s) URL, unreachable, oversized, or unparseable.",
                    content = @Content(mediaType = "application/problem+json"))})
    @PostMapping(value = "/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FilingIngestionResult ingestFromUrl(@Valid @RequestBody UrlIngestRequest request) {
        URI uri = parseUri(request.url());
        String filename = (request.filename() == null || request.filename().isBlank())
                ? filenameFromUri(uri)
                : request.filename();

        byte[] bytes = fetch(uri);
        log.info("Fetched {} bytes from {}", bytes.length, uri);

        FilingMetadata metadata = metadataResolver.resolve(filename, new FilingMetadataResolver.Overrides(
                request.ticker(), request.companyName(), request.formType(), request.fiscalYear(),
                request.fiscalPeriod(),
                parseDate(request.periodEndDate(), "periodEndDate"),
                parseDate(request.filingDate(), "filingDate")));

        return ingestionService.ingest(named(bytes, filename), metadata, request.force());
    }

    /**
     * Everything indexed, with the current edition of each filing flagged.
     */
    @Operation(
            summary = "List indexed filings",
            description = "Every ingested document. `currentVersion` marks the newest edition of "
                    + "each form type per company; everything else is historical but still searchable.")
    @GetMapping
    public FilingInventory list() {
        List<FilingDocument> all = searchService.registeredFilings();
        List<UUID> latestIds = searchService.latestFilings().stream().map(FilingDocument::id).toList();

        List<FilingSummary> summaries = all.stream()
                .map(document -> new FilingSummary(
                        document.id(), document.ticker(), document.companyName(),
                        document.formType().label(), document.fiscalYear(), document.fiscalPeriod(),
                        document.periodEndDate(), document.filingDate(), document.sourceFilename(),
                        document.chunkCount(), document.contentSha256(), document.ingestedAt().toString(),
                        latestIds.contains(document.id())))
                .toList();

        return new FilingInventory(
                summaries.size(), filingCatalog.totalChunks(), filingCatalog.vectorTable(), summaries);
    }

    public record FilingSummary(
            UUID documentId, String ticker, String companyName, String formType,
            Integer fiscalYear, String fiscalPeriod, LocalDate periodEndDate, LocalDate filingDate,
            String sourceFilename, Integer chunkCount, String contentSha256, String ingestedAt,
            boolean currentVersion) {
    }

    public record FilingInventory(
            int filingCount, long totalChunks, String vectorTable, List<FilingSummary> filings) {
    }

    /**
     * Fiscal years available for a company, so a client can offer a version picker.
     */
    @Operation(
            summary = "Which versions exist",
            description = "Fiscal years on record plus the current edition, so a client can offer a version picker.")
    @GetMapping("/versions")
    public Map<String, Object> versions(
            @RequestParam(value = "ticker", required = false) String ticker,
            @RequestParam(value = "formType", required = false) String formType) {

        FilingType type = (formType == null || formType.isBlank()) ? null : FilingType.from(formType);
        return Map.of(
                "ticker", ticker == null ? "(all)" : ticker.toUpperCase(),
                "formType", type == null ? "(all)" : type.label(),
                "fiscalYears", searchService.availableYears(ticker, type),
                "current", searchService.latestFilings().stream().map(FilingDocument::citation).toList());
    }

    @Operation(
            summary = "Delete a filing",
            description = "Removes the registry row and every chunk that references it. "
                    + "If this was the current edition, the next-newest becomes current automatically.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted."),
            @ApiResponse(responseCode = "422", description = "No filing with that id.",
                    content = @Content(mediaType = "application/problem+json"))})
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "documentId from POST /api/filings or GET /api/filings")
            @PathVariable UUID documentId) {
        FilingDocument document = searchService.registeredFilings().stream()
                .filter(candidate -> candidate.id().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new FilingIngestionException(
                        "No filing with id " + documentId));
        ingestionService.deleteDocument(document);
    }

    public record FilingQuestion(
            @NotBlank(message = "question must not be blank")
            @Size(max = 4000, message = "question must be at most 4000 characters")
            String question) {
    }

    public record FilingAnswer(String answer) {
    }

    /**
     * Ask a question answered strictly from the indexed filings.
     *
     * <p>Distinct from {@code /api/agent/ask}: no market tools are available here, so the
     * answer can only come from retrieved document text.
     */
    @Operation(
            summary = "Ask a question answered only from the filings",
            description = """
                    No market tools are available on this path, so the answer can only come from \
                    retrieved document text.
                    
                    Unlike `/api/agent/ask`, this searches **all versions** — the retrieval advisor \
                    has no parameters through which to express a version scope. Use the agent \
                    endpoint when latest-only matters.""")
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FilingAnswer ask(@Valid @RequestBody FilingQuestion request) {
        log.info("Filing question: {}", request.question());
        return new FilingAnswer(filingsChatClient.prompt()
                .user(request.question())
                .call()
                .content());
    }

    @Operation(
            summary = "Raw semantic search, no model call",
            description = """
                    Returns the matching chunks with their similarity scores and metadata. Costs \
                    one embedding call and no completion — the cheapest way to tune `topK` and \
                    `jspringverse.rag.similarity-threshold`, or to see why the agent answered as \
                    it did.
                    
                    `documentsSearched` reports which filings were actually in scope.""")
    @GetMapping("/search")
    public Map<String, Object> search(
            @Parameter(description = "Topic or question.", example = "supply chain concentration risk")
            @RequestParam("query") String query,
            @Parameter(description = "Restrict to one company.", example = "AAPL")
            @RequestParam(value = "ticker", required = false) String ticker,
            @Parameter(description = "Restrict to a form type.", schema = @Schema(allowableValues = {"10-K", "10-Q", "8-K", "DEF 14A"}))
            @RequestParam(value = "formType", required = false) String formType,
            @Parameter(description = "Restrict to a fiscal year. Implies versionScope=ALL.", example = "2024")
            @RequestParam(value = "fiscalYear", required = false) Integer fiscalYear,
            @Parameter(description = "LATEST searches only the newest filing per company; ALL searches every version.",
                    schema = @Schema(allowableValues = {"LATEST", "ALL"}, defaultValue = "LATEST"))
            @RequestParam(value = "versionScope", defaultValue = "LATEST") String versionScope,
            @Parameter(description = "Chunks to return. Defaults to jspringverse.rag.top-k (8).")
            @RequestParam(value = "topK", required = false) Integer topK) {

        FilingSearchService.SearchOutcome outcome = searchService.search(
                query,
                new FilingSearchService.FilingFilter(
                        ticker,
                        (formType == null || formType.isBlank()) ? null : FilingType.from(formType),
                        fiscalYear,
                        VersionScope.from(versionScope)),
                topK);

        return Map.of(
                "query", query,
                "versionScope", outcome.scope().name(),
                "documentsSearched", outcome.documentsSearched().stream()
                        .map(FilingDocument::citation).toList(),
                "hitCount", outcome.hits().size(),
                "hits", outcome.hits().stream().map(hit -> Map.of(
                        "score", hit.getScore() == null ? 0.0 : hit.getScore(),
                        "metadata", hit.getMetadata(),
                        "text", hit.getText())).toList());
    }

    // --- Helpers ------------------------------------------------------------------------

    /**
     * Materialises the upload into memory.
     *
     * <p>The ingestion pipeline reads the document twice — once to hash, once to extract —
     * so the resource must be re-readable. {@code InputStreamResource} is not. Bounded by
     * {@code spring.servlet.multipart.max-file-size}.
     */
    private static Resource toResource(MultipartFile file) {
        try {
            return named(file.getBytes(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw new FilingIngestionException(
                    "Could not read uploaded file '%s'".formatted(file.getOriginalFilename()), ex);
        }
    }

    /**
     * A re-readable resource that keeps its filename, which Tika uses as a parsing hint.
     */
    private static Resource named(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private byte[] fetch(URI uri) {
        try {
            byte[] body = downloadClient.get()
                    .uri(uri)
                    // SEC EDGAR rejects requests without a descriptive User-Agent and is
                    // explicit in its access policy that one is required.
                    .header("User-Agent", "JSpringVerse Filing Agent (contact: admin@example.com)")
                    .retrieve()
                    .body(byte[].class);

            if (body == null || body.length == 0) {
                throw new FilingIngestionException("Fetched nothing from " + uri);
            }
            if (body.length > MAX_FETCH_BYTES) {
                throw new FilingIngestionException(
                        "Document at %s is %d bytes, over the %d byte limit"
                                .formatted(uri, body.length, MAX_FETCH_BYTES));
            }
            return body;
        } catch (FilingIngestionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FilingIngestionException(
                    "Could not fetch %s: %s".formatted(uri, ex.getMessage()), ex);
        }
    }

    private static URI parseUri(String url) {
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme();
            // Without this, a caller could make the server read file:// or probe internal
            // services on the network it sits in.
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new FilingIngestionException("Only http and https URLs are accepted, got: " + url);
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new FilingIngestionException("Malformed URL: " + url, ex);
        }
    }

    private static String filenameFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "filing-from-url";
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception ex) {
            throw new FilingIngestionException(
                    "%s must be an ISO date such as 2024-11-01, got '%s'".formatted(field, raw));
        }
    }
}
