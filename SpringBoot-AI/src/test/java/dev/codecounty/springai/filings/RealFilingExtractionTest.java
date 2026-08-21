package dev.codecounty.springai.filings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the extract → section → chunk stages against a real 10-K.
 *
 * <p>Synthetic fixtures cannot tell you whether Tika actually gets text out of a filing PDF,
 * or whether the Item headings survive extraction well enough to be matched — and both are
 * silent failure modes: a scanned PDF yields zero chunks, and unmatched headings collapse
 * the whole document into one unusable "Full document" section.
 *
 * <p>No database or API key required: this stops short of embedding.
 *
 * <p>Skipped automatically when the sample filing is absent, so the build stays green on a
 * checkout without it.
 */
@EnabledIf("sampleFilingPresent")
class RealFilingExtractionTest {

    private static final String SAMPLE = "docs/10k/Apple_10K-2025.pdf";

    static boolean sampleFilingPresent() {
        return new ClassPathResource(SAMPLE).exists();
    }

    @Test
    @DisplayName("extracts, sections and chunks a real Apple 10-K end to end")
    void processesRealFiling() {
        // 1. Extract
        List<Document> extracted = new TikaDocumentReader(new ClassPathResource(SAMPLE)).get();
        String text = String.join("\n", extracted.stream().map(Document::getText).toList());

        assertThat(text).isNotBlank();
        // A real 10-K runs to tens of thousands of words. Anything much smaller means the
        // PDF is image-only and Tika returned page furniture instead of content.
        assertThat(text.length()).isGreaterThan(50_000);

        // 2. Section
        List<FilingSectionSplitter.Section> sections = new FilingSectionSplitter().split(text);

        assertThat(sections).hasSizeGreaterThan(5);
        assertThat(sections).extracting(FilingSectionSplitter.Section::title)
                .isNotEqualTo(List.of("Full document"));

        List<String> titles = sections.stream().map(FilingSectionSplitter.Section::title).toList();
        assertThat(titles).anyMatch(title -> title.startsWith("Item 1A"));   // Risk Factors
        assertThat(titles).anyMatch(title -> title.startsWith("Item 7"));    // MD&A

        // 3. Chunk
        TokenTextSplitter tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(500)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(50_000)
                .withKeepSeparator(true)
                .build();

        int totalChunks = 0;
        for (FilingSectionSplitter.Section section : sections) {
            totalChunks += tokenSplitter.apply(List.of(new Document(section.text()))).size();
        }
        assertThat(totalChunks).isGreaterThan(20);

        System.out.printf("%nApple 10-K: %,d chars → %d sections → %d chunks%n",
                text.length(), sections.size(), totalChunks);
        sections.forEach(section -> System.out.printf("  %-70s %,7d chars%n",
                truncate(section.title()), section.text().length()));
    }

    private static String truncate(String value) {
        return value.length() <= 68 ? value : value.substring(0, 65) + "...";
    }
}
