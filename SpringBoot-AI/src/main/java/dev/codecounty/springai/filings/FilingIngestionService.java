package dev.codecounty.springai.filings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an uploaded 10-K / 10-Q into embedded, queryable chunks.
 *
 * <p>Pipeline: <b>hash</b> (SHA-256, dedup gate) → <b>extract</b> (Tika: PDF/HTML/DOCX) →
 * <b>section</b> ({@link FilingSectionSplitter}, splits on SEC Item headings) →
 * <b>chunk</b> (token splitter, within a section only) → <b>enrich</b> (provenance
 * metadata incl. document id) → <b>register</b> ({@code filing_document}) →
 * <b>embed and store</b> (pgvector, in batches).
 *
 * <p><b>Versioning, not replacement.</b> Earlier editions of a filing are kept and stay
 * searchable — a 2023 10-K is the correct source for a question about 2023. What changes is
 * the default: {@link VersionScope#LATEST} restricts retrieval to the newest edition unless
 * the caller asks for history. Duplicate <i>content</i> is still rejected by hash, so
 * re-uploading the same file is a cheap no-op rather than a second copy in the index.
 */
@Service
public class FilingIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FilingIngestionService.class);

    private final VectorStore vectorStore;
    private final FilingSectionSplitter sectionSplitter;
    private final FilingRegistry registry;
    private final RagProperties ragProperties;
    private final TokenTextSplitter tokenSplitter;
    private final String embeddingModel;
    private final String vectorTable;

    public FilingIngestionService(
            VectorStore vectorStore,
            FilingSectionSplitter sectionSplitter,
            FilingRegistry registry,
            RagProperties ragProperties,
            @Value("${spring.ai.model.embedding:openai}") String embeddingModel,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String vectorTable) {

        this.vectorStore = vectorStore;
        this.sectionSplitter = sectionSplitter;
        this.registry = registry;
        this.ragProperties = ragProperties;
        this.embeddingModel = embeddingModel;
        this.vectorTable = vectorTable;

        this.tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(ragProperties.chunkSizeTokens())
                .withMinChunkSizeChars(ragProperties.minChunkSizeChars())
                .withMinChunkLengthToEmbed(ragProperties.minChunkLengthToEmbed())
                .withMaxNumChunks(50_000)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * Extracts, chunks, embeds and stores a filing.
     *
     * @param resource the document. Must be re-readable: it is consumed once for hashing
     *                 and again for extraction. Callers pass a {@code ByteArrayResource}.
     * @param metadata provenance to stamp on every chunk
     * @param force    when true, an existing document with the same content hash is deleted
     *                 and re-ingested instead of skipped
     * @throws FilingIngestionException if the document yields no usable text
     */
    public FilingIngestionResult ingest(Resource resource, FilingMetadata metadata, boolean force) {
        Instant startedAt = Instant.now();

        // Hash first: the whole point is to avoid paying for extraction and embedding on a
        // document already in the index.
        String sha256 = ContentHasher.sha256(resource);
        Optional<FilingDocument> existing = registry.findByContentHash(sha256, embeddingModel);

        if (existing.isPresent() && !force) {
            FilingDocument document = existing.get();
            log.info("Skipping duplicate upload of {} (sha256 {}…)",
                    document.citation(), sha256.substring(0, 12));
            return FilingIngestionResult.duplicate(document, isLatest(document));
        }

        if (existing.isPresent()) {
            log.info("force=true — replacing existing {} before re-ingesting", existing.get().citation());
            deleteDocument(existing.get());
        }

        UUID documentId = UUID.randomUUID();
        FilingMetadata identified = metadata.withDocumentId(documentId);

        log.info("Ingesting {} from '{}' (sha256 {}…)",
                identified.citation(), identified.sourceFile(), sha256.substring(0, 12));

        String text = extractText(resource, identified);
        List<FilingSectionSplitter.Section> sections = sectionSplitter.split(text);
        List<Document> chunks = toChunks(sections, identified);

        if (chunks.isEmpty()) {
            throw new FilingIngestionException(
                    "No chunks were produced from '%s'. The file may be image-only (scanned) "
                            .formatted(identified.sourceFile())
                            + "or password protected — try a text-based PDF or the EDGAR HTML version.");
        }

        FilingDocument document = new FilingDocument(
                documentId, sha256, embeddingModel, vectorTable,
                identified.ticker(), identified.companyName(), identified.formType(),
                identified.fiscalYear(), identified.fiscalPeriod(), identified.periodEndDate(),
                identified.filingDate(), identified.sourceFile(), null,
                contentLength(resource), (long) text.length(), sections.size(), chunks.size(),
                Instant.now());

        registry.insert(document);
        try {
            storeInBatches(chunks);
        }
        catch (RuntimeException ex) {
            // Leaving a registry row without vectors would make the document look ingested
            // and permanently deduplicate future uploads of a filing that is not searchable.
            log.error("Embedding failed for {} — rolling back registry row", identified.citation(), ex);
            deleteDocument(document);
            throw new FilingIngestionException(
                    "Failed to embed '%s': %s".formatted(identified.sourceFile(), ex.getMessage()), ex);
        }

        Duration duration = Duration.between(startedAt, Instant.now());
        log.info("Ingested {} — {} chunks across {} sections in {}s",
                identified.citation(), chunks.size(), sections.size(), duration.toSeconds());

        List<String> sectionTitles = new ArrayList<>(new LinkedHashSet<>(
                sections.stream().map(FilingSectionSplitter.Section::title).toList()));

        return new FilingIngestionResult(
                force ? FilingIngestionResult.Status.REINGESTED : FilingIngestionResult.Status.INGESTED,
                documentId,
                sha256,
                identified.citation(),
                identified.sourceFile(),
                isLatest(document),
                sections.size(),
                chunks.size(),
                text.length(),
                duration,
                sectionTitles,
                null);
    }

    /** Removes a document's registry row and every chunk that references it. */
    public void deleteDocument(FilingDocument document) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder.eq(FilingMetadata.KEY_DOCUMENT_ID, document.id().toString()).build());
        registry.deleteById(document.id());
        log.info("Deleted {} and its {} chunks", document.citation(), document.chunkCount());
    }

    /** Whether this document is currently the newest of its (ticker, form type). */
    private boolean isLatest(FilingDocument document) {
        return registry.findLatest(embeddingModel, document.ticker(), document.formType()).stream()
                .anyMatch(latest -> latest.id().equals(document.id()));
    }

    private String extractText(Resource resource, FilingMetadata metadata) {
        try {
            List<Document> extracted = new TikaDocumentReader(resource).get();
            String text = String.join("\n", extracted.stream().map(Document::getText).toList());
            if (text.isBlank()) {
                throw new FilingIngestionException(
                        "No text could be extracted from '%s'.".formatted(metadata.sourceFile()));
            }
            return text;
        }
        catch (FilingIngestionException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new FilingIngestionException(
                    "Failed to parse '%s': %s".formatted(metadata.sourceFile(), ex.getMessage()), ex);
        }
    }

    /**
     * Chunks each section independently so no chunk straddles a section boundary, then
     * stamps section and position metadata onto every chunk.
     */
    private List<Document> toChunks(List<FilingSectionSplitter.Section> sections, FilingMetadata metadata) {
        Map<String, Object> baseMetadata = metadata.toMetadataMap();
        List<Document> allChunks = new ArrayList<>();
        int chunkIndex = 0;

        for (FilingSectionSplitter.Section section : sections) {
            Map<String, Object> sectionMetadata = new LinkedHashMap<>(baseMetadata);
            sectionMetadata.put(FilingMetadata.KEY_SECTION, section.title());

            // The splitter copies parent metadata onto every child chunk it produces.
            List<Document> sectionChunks =
                    tokenSplitter.apply(List.of(new Document(section.text(), sectionMetadata)));

            for (Document chunk : sectionChunks) {
                chunk.getMetadata().put(FilingMetadata.KEY_CHUNK_INDEX, chunkIndex++);
                allChunks.add(chunk);
            }
        }
        return allChunks;
    }

    /**
     * Embeds and writes in batches.
     *
     * <p>A 10-K commonly produces several hundred chunks; handing them to the embedding
     * model in one call risks exceeding the provider's per-request payload limit and makes
     * a single transient failure lose the whole ingest.
     */
    private void storeInBatches(List<Document> chunks) {
        int batchSize = ragProperties.embeddingBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            log.debug("Embedding chunks {}-{} of {}", start, end, chunks.size());
            vectorStore.add(chunks.subList(start, end));
        }
    }

    private static Long contentLength(Resource resource) {
        try {
            return resource.contentLength();
        }
        catch (Exception ex) {
            return null;
        }
    }
}
