package dev.codecounty.springai.filings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section splitting is the step that decides whether filing retrieval is usable, so it is
 * tested against the shapes real 10-Ks actually take — including the table of contents
 * that repeats every Item heading before any content appears.
 */
class FilingSectionSplitterTest {

    private final FilingSectionSplitter splitter = new FilingSectionSplitter();

    @Test
    @DisplayName("splits on Item headings and labels each section")
    void splitsOnItemHeadings() {
        String filing = """
                UNITED STATES SECURITIES AND EXCHANGE COMMISSION
                Annual Report pursuant to Section 13 of the Securities Exchange Act of 1934.
                %s

                Item 1. Business
                %s

                Item 1A. Risk Factors
                %s

                Item 3. Legal Proceedings
                %s
                """.formatted(filler("cover"), filler("business"), filler("risk"), filler("legal"));

        List<FilingSectionSplitter.Section> sections = splitter.split(filing);

        assertThat(sections).extracting(FilingSectionSplitter.Section::title)
                .containsExactly(
                        "Cover and front matter",
                        "Item 1. Business",
                        "Item 1A. Risk Factors",
                        "Item 3. Legal Proceedings");

        assertThat(sections.get(2).text()).contains("risk");
        assertThat(sections.get(2).text()).doesNotContain("legal");
    }

    @Test
    @DisplayName("absorbs the table of contents instead of emitting empty sections")
    void absorbsTableOfContents() {
        String filing = """
                TABLE OF CONTENTS
                Item 1. Business .......... 4
                Item 1A. Risk Factors ..... 12
                Item 7. Management's Discussion and Analysis ..... 40

                Item 1. Business
                %s

                Item 1A. Risk Factors
                %s
                """.formatted(filler("business"), filler("risk"));

        List<FilingSectionSplitter.Section> sections = splitter.split(filing);

        // The three TOC lines must not become their own sections; only the two real ones
        // survive, otherwise near-empty duplicates compete with real content at retrieval.
        assertThat(sections).extracting(FilingSectionSplitter.Section::title)
                .containsExactly("Item 1. Business", "Item 1A. Risk Factors");
    }

    @Test
    @DisplayName("ignores mid-sentence cross references to other items")
    void ignoresCrossReferences() {
        String filing = """
                Item 7. Management's Discussion and Analysis
                %s
                For further detail see Item 8 of this report and Item 1A above.
                %s
                """.formatted(filler("mdna"), filler("more"));

        List<FilingSectionSplitter.Section> sections = splitter.split(filing);

        assertThat(sections).hasSize(1);
        assertThat(sections.getFirst().title())
                .isEqualTo("Item 7. Management's Discussion and Analysis");
    }

    @Test
    @DisplayName("drops the trailing TOC row that would otherwise absorb the front matter")
    void dropsTrailingTableOfContentsRow() {
        // Regression: in Apple's FY2025 10-K the *last* TOC row survived the short-section
        // filter, because the gap to the first real heading gave it a plausible body.
        String filing = """
                TABLE OF CONTENTS
                Item 1. Business .......... 4
                Item 16. Form 10-K Summary        57

                %s

                Item 1. Business
                %s
                """.formatted(filler("cover page and filer information"), filler("business"));

        List<FilingSectionSplitter.Section> sections = splitter.split(filing);

        assertThat(sections).extracting(FilingSectionSplitter.Section::title)
                .containsExactly("Cover and front matter", "Item 1. Business")
                .doesNotContain("Item 16. Form 10-K Summary");
    }

    @Test
    @DisplayName("ignores back-references that break ascending item order")
    void ignoresOutOfOrderBackReferences() {
        // The exhibit index at the end of a 10-K names Item 7 and Item 8 again. Treating
        // those as section starts splits the financial statements under the wrong heading.
        String filing = """
                Item 7. Management's Discussion and Analysis
                %s

                Item 8. Financial Statements and Supplementary Data
                %s

                Item 15. Exhibit and Financial Statement Schedules
                %s

                Item 7. Incorporated by reference
                %s

                Item 8. Incorporated by reference
                %s
                """.formatted(filler("mdna"), filler("financials"), filler("exhibits"),
                filler("backref one"), filler("backref two"));

        List<FilingSectionSplitter.Section> sections = splitter.split(filing);

        assertThat(sections).extracting(FilingSectionSplitter.Section::title)
                .containsExactly(
                        "Item 7. Management's Discussion and Analysis",
                        "Item 8. Financial Statements and Supplementary Data",
                        "Item 15. Exhibit and Financial Statement Schedules");

        // The back-reference text is folded into the last real section, never discarded.
        assertThat(sections.getLast().text()).contains("backref one", "backref two");
    }

    @Test
    @DisplayName("orders lettered sub-items after their parent item")
    void ordersLetteredSubItems() {
        String filing = """
                Item 7. Management's Discussion and Analysis
                %s

                Item 7A. Quantitative and Qualitative Disclosures About Market Risk
                %s

                Item 8. Financial Statements
                %s
                """.formatted(filler("mdna"), filler("market risk"), filler("financials"));

        assertThat(splitter.split(filing)).extracting(FilingSectionSplitter.Section::title)
                .containsExactly(
                        "Item 7. Management's Discussion and Analysis",
                        "Item 7A. Quantitative and Qualitative Disclosures About Market Risk",
                        "Item 8. Financial Statements");
    }

    @Test
    @DisplayName("falls back to a single section when no headings are recognised")
    void fallsBackForUnstructuredText() {
        List<FilingSectionSplitter.Section> sections = splitter.split(filler("plain prose"));

        assertThat(sections).hasSize(1);
        assertThat(sections.getFirst().title()).isEqualTo("Full document");
    }

    @Test
    @DisplayName("returns nothing for blank input")
    void handlesBlankInput() {
        assertThat(splitter.split("   ")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    /** Body text long enough to clear the minimum-section threshold. */
    private static String filler(String marker) {
        return (marker + " content sentence that carries enough words to look like real filing prose. ")
                .repeat(12);
    }
}
