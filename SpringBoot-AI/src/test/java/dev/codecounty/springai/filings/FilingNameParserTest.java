package dev.codecounty.springai.filings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilingNameParserTest {

    private final FilingNameParser parser = new FilingNameParser();

    @Test
    @DisplayName("parses the documented convention: Apple_10K-2025.pdf")
    void parsesCanonicalName() {
        FilingNameParser.ParsedName parsed = parse("Apple_10K-2025.pdf");

        assertThat(parsed.companyName()).isEqualTo("Apple");
        assertThat(parsed.formType()).isEqualTo(FilingType.FORM_10K);
        assertThat(parsed.fiscalYear()).isEqualTo(2025);
        assertThat(parsed.fiscalPeriod()).isNull();
        assertThat(parsed.periodEndDate()).isNull();
    }

    @Test
    @DisplayName("accepts interchangeable separators and hyphenated form types")
    void acceptsSeparatorVariants() {
        assertThat(parse("AAPL_10-K_2024.pdf").formType()).isEqualTo(FilingType.FORM_10K);
        assertThat(parse("AAPL_10-K_2024.pdf").fiscalYear()).isEqualTo(2024);
        assertThat(parse("Microsoft 10-K 2024.pdf").companyName()).isEqualTo("Microsoft");
        assertThat(parse("Alphabet-10Q-2025.htm").formType()).isEqualTo(FilingType.FORM_10Q);
    }

    @Test
    @DisplayName("extracts a quarter from a 10-Q filename")
    void parsesQuarter() {
        FilingNameParser.ParsedName parsed = parse("Apple_10Q-2025-Q3.pdf");

        assertThat(parsed.formType()).isEqualTo(FilingType.FORM_10Q);
        assertThat(parsed.fiscalYear()).isEqualTo(2025);
        assertThat(parsed.fiscalPeriod()).isEqualTo("Q3");
    }

    @Test
    @DisplayName("extracts a full period end date and derives the year from it")
    void parsesPeriodEndDate() {
        FilingNameParser.ParsedName parsed = parse("Microsoft_10K-2024-06-30.pdf");

        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(parsed.fiscalYear()).isEqualTo(2024);
    }

    @Test
    @DisplayName("keeps the year when the date portion is not a real date")
    void toleratesImpossibleDate() {
        FilingNameParser.ParsedName parsed = parse("Foo_10K-2025-99-99.pdf");

        assertThat(parsed.periodEndDate()).isNull();
        assertThat(parsed.fiscalYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("handles multi-word company names")
    void parsesMultiWordCompany() {
        assertThat(parse("Berkshire_Hathaway_10K-2024.pdf").companyName())
                .isEqualTo("Berkshire Hathaway");
    }

    @Test
    @DisplayName("returns empty rather than guessing when the name does not follow the convention")
    void returnsEmptyForUnrecognisedNames() {
        assertThat(parser.parse("scan001.pdf")).isEmpty();
        assertThat(parser.parse("annual report.pdf")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    private FilingNameParser.ParsedName parse(String filename) {
        Optional<FilingNameParser.ParsedName> parsed = parser.parse(filename);
        assertThat(parsed).as("expected '%s' to parse", filename).isPresent();
        return parsed.get();
    }
}
