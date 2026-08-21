package dev.codecounty.springai.filings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHasherTest {

    @Test
    @DisplayName("produces the known SHA-256 of a known input")
    void matchesKnownDigest() {
        // Fixed vector, so a future refactor cannot silently change the hash and invalidate
        // every dedup record already in the registry.
        String hash = ContentHasher.sha256(
                new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(hash).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("is stable across calls and sensitive to a single byte")
    void isStableAndSensitive() {
        var first = new ByteArrayResource("Apple 10-K 2025".getBytes(StandardCharsets.UTF_8));
        var same = new ByteArrayResource("Apple 10-K 2025".getBytes(StandardCharsets.UTF_8));
        var different = new ByteArrayResource("Apple 10-K 2024".getBytes(StandardCharsets.UTF_8));

        assertThat(ContentHasher.sha256(first)).isEqualTo(ContentHasher.sha256(same));
        assertThat(ContentHasher.sha256(first)).isNotEqualTo(ContentHasher.sha256(different));
    }

    @Test
    @DisplayName("hashes a real filing PDF consistently across repeated reads")
    @EnabledIf("sampleFilingPresent")
    void hashesRealFiling() {
        var resource = new ClassPathResource("docs/10k/Apple_10K-2025.pdf");

        String first = ContentHasher.sha256(resource);
        String second = ContentHasher.sha256(resource);

        assertThat(first).hasSize(64).isEqualTo(second);
        System.out.println("Apple_10K-2025.pdf sha256 = " + first);
    }

    static boolean sampleFilingPresent() {
        return new ClassPathResource("docs/10k/Apple_10K-2025.pdf").exists();
    }
}
