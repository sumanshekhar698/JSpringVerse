package dev.codecounty.springai.filings;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a 10-K or 10-Q into its numbered {@code Item} sections before token chunking.
 *
 * <p>Why this exists: a flat token splitter cuts a 10-K wherever the window runs out,
 * routinely merging the tail of "Risk Factors" with the head of "Legal Proceedings" into
 * one chunk. Retrieval then returns a chunk that is half about the wrong subject, and the
 * agent cannot cite which part of the filing an answer came from.
 *
 * <p>Splitting on item boundaries first means every chunk belongs to exactly one section
 * and carries that section as metadata, so the agent can answer "what risks did they
 * disclose" by filtering to Item 1A rather than hoping semantic similarity lands there.
 *
 * <p>Best-effort by design: filings vary in formatting, and a document with no recognisable
 * headers falls back to a single {@code Full document} section rather than failing.
 */
@Component
public class FilingSectionSplitter {

    /**
     * Matches a line that is an Item heading, e.g. {@code Item 1A. Risk Factors}.
     * Anchored to line start so mid-sentence cross-references ("see Item 7 below") are ignored.
     */
    private static final Pattern ITEM_HEADING = Pattern.compile(
            "(?im)^[ \\t]*item[ \\t]+(\\d{1,2}[A-Z]?)[ \\t]*[.:\\-–]?[ \\t]*(.{0,90}?)[ \\t]*$");

    /**
     * A "section" shorter than this is a table-of-contents entry, not real content.
     * Every 10-K repeats its full item list up front; without this the TOC would produce
     * a duplicate, empty section for each item and outrank real content at retrieval time.
     */
    private static final int MIN_SECTION_CHARS = 400;

    /**
     * A heading line ending in a bare page number is a contents-table row, e.g.
     * {@code Item 16. Form 10-K Summary        57}.
     *
     * <p>Length alone does not catch these. Most TOC rows are folded away by
     * {@link #MIN_SECTION_CHARS} because the next row follows immediately — but the
     * <i>last</i> row has no successor until the first real section begins, so it absorbs
     * the intervening text and survives as a bogus section. Verified against Apple's FY2025
     * 10-K, where it produced a spurious 2 KB "Item 16" section directly after the cover.
     */
    private static final Pattern TOC_PAGE_NUMBER = Pattern.compile(".*?[\\s.]\\d{1,3}$");

    /** Canonical titles, used when the heading text itself is missing or mangled. */
    private static final Map<String, String> KNOWN_ITEMS = Map.ofEntries(
            Map.entry("1", "Business"),
            Map.entry("1A", "Risk Factors"),
            Map.entry("1B", "Unresolved Staff Comments"),
            Map.entry("2", "Properties"),
            Map.entry("3", "Legal Proceedings"),
            Map.entry("4", "Mine Safety Disclosures"),
            Map.entry("5", "Market for Registrant's Common Equity"),
            Map.entry("6", "Selected Financial Data"),
            Map.entry("7", "Management's Discussion and Analysis"),
            Map.entry("7A", "Quantitative and Qualitative Disclosures About Market Risk"),
            Map.entry("8", "Financial Statements and Supplementary Data"),
            Map.entry("9", "Changes in and Disagreements with Accountants"),
            Map.entry("9A", "Controls and Procedures"),
            Map.entry("10", "Directors, Executive Officers and Corporate Governance"),
            Map.entry("11", "Executive Compensation"),
            Map.entry("12", "Security Ownership of Certain Beneficial Owners"),
            Map.entry("13", "Certain Relationships and Related Transactions"),
            Map.entry("14", "Principal Accountant Fees and Services"),
            Map.entry("15", "Exhibits and Financial Statement Schedules"));

    /** One contiguous run of filing text with a label. */
    public record Section(String title, String text) {
    }

    /**
     * @param text full extracted text of the filing
     * @return sections in document order; never empty for non-blank input
     */
    public List<Section> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        record Heading(int start, int bodyStart, String itemNumber, String title) {
        }

        List<Heading> candidates = new ArrayList<>();
        Matcher matcher = ITEM_HEADING.matcher(text);
        while (matcher.find()) {
            String headingText = matcher.group(2) == null ? "" : matcher.group(2).strip();
            if (TOC_PAGE_NUMBER.matcher(headingText).matches()) {
                continue;
            }
            candidates.add(new Heading(matcher.start(), matcher.end(),
                    matcher.group(1).toUpperCase(Locale.ROOT),
                    formatTitle(matcher.group(1), headingText)));
        }

        // Real filings number their items in ascending order. Anything that breaks that
        // order is a back-reference, not a section start — the exhibit index at the end of
        // a 10-K cites "Item 7" and "Item 8" again, and treating those as new sections
        // splits the financial statements off under the wrong heading. Keeping only the
        // longest ascending run recovers the document's actual spine.
        List<Heading> headings = longestAscendingRun(candidates, Heading::itemNumber);

        if (headings.isEmpty()) {
            return List.of(new Section("Full document", text));
        }

        List<Section> sections = new ArrayList<>();

        // Anything before the first heading (cover page, filer info) is still worth indexing.
        String preamble = text.substring(0, headings.getFirst().start()).strip();
        if (preamble.length() >= MIN_SECTION_CHARS) {
            sections.add(new Section("Cover and front matter", preamble));
        }

        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int end = (i + 1 < headings.size()) ? headings.get(i + 1).start() : text.length();
            String body = text.substring(heading.bodyStart(), end).strip();

            if (body.length() < MIN_SECTION_CHARS) {
                // TOC entry or a stub. Fold it into the previous section so no text is lost,
                // rather than emitting a near-empty chunk that pollutes retrieval.
                if (!sections.isEmpty()) {
                    Section previous = sections.removeLast();
                    sections.add(new Section(previous.title(),
                            previous.text() + "\n" + heading.title() + "\n" + body));
                }
                continue;
            }
            sections.add(new Section(heading.title(), body));
        }

        return sections.isEmpty() ? List.of(new Section("Full document", text)) : sections;
    }

    /**
     * Longest strictly-ascending subsequence of headings by item number, preserving
     * document order. O(n²), which is irrelevant at the few dozen headings a filing has.
     */
    private static <T> List<T> longestAscendingRun(List<T> items, java.util.function.Function<T, String> keyFn) {
        int size = items.size();
        if (size == 0) {
            return List.of();
        }

        int[] runLength = new int[size];
        int[] previous = new int[size];
        int bestEnd = 0;

        for (int i = 0; i < size; i++) {
            runLength[i] = 1;
            previous[i] = -1;
            for (int j = 0; j < i; j++) {
                if (runLength[j] + 1 > runLength[i]
                        && compareItemNumbers(keyFn.apply(items.get(j)), keyFn.apply(items.get(i))) < 0) {
                    runLength[i] = runLength[j] + 1;
                    previous[i] = j;
                }
            }
            if (runLength[i] > runLength[bestEnd]) {
                bestEnd = i;
            }
        }

        List<T> run = new ArrayList<>(runLength[bestEnd]);
        for (int i = bestEnd; i >= 0; i = previous[i]) {
            run.add(items.get(i));
        }
        java.util.Collections.reverse(run);
        return run;
    }

    /** Orders item numbers the way a filing does: 1 &lt; 1A &lt; 1B &lt; 2 &lt; 7 &lt; 7A &lt; 10. */
    private static int compareItemNumbers(String left, String right) {
        int leftDigits = leadingDigits(left);
        int rightDigits = leadingDigits(right);
        if (leftDigits != rightDigits) {
            return Integer.compare(leftDigits, rightDigits);
        }
        // Same number: the bare item precedes its lettered sub-items (7 before 7A).
        return left.substring(String.valueOf(leftDigits).length())
                .compareTo(right.substring(String.valueOf(rightDigits).length()));
    }

    private static int leadingDigits(String itemNumber) {
        int end = 0;
        while (end < itemNumber.length() && Character.isDigit(itemNumber.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(itemNumber.substring(0, end));
    }

    private static String formatTitle(String itemNumber, String headingText) {
        String number = itemNumber.toUpperCase(Locale.ROOT);
        String title = headingText == null ? "" : headingText.strip();
        // Strip trailing dot leaders and page numbers left over from a contents table.
        title = title.replaceAll("[.\\s ]{3,}\\d*$", "").strip();

        if (title.isBlank() || title.length() < 3) {
            title = KNOWN_ITEMS.getOrDefault(number, "");
        }
        return title.isBlank()
                ? "Item " + number
                : "Item " + number + ". " + title;
    }
}
