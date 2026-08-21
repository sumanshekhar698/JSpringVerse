package dev.codecounty.springai.filings;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over raw document bytes, used as the duplicate-detection key.
 *
 * <p>Hashing the bytes rather than the extracted text is deliberate: extraction is the
 * expensive step we are trying to skip, and it is also non-deterministic across Tika
 * versions, so a text hash would spuriously miss on a library upgrade.
 */
public final class ContentHasher {

    /** Streams in 8 KB blocks so a 60 MB filing never lands in memory whole just to be hashed. */
    private static final int BUFFER_SIZE = 8192;

    private ContentHasher() {
    }

    /** @return lowercase hex SHA-256 of the resource's bytes */
    public static String sha256(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return sha256(input);
        }
        catch (IOException ex) {
            throw new FilingIngestionException(
                    "Could not read '%s' to compute its content hash".formatted(resource.getFilename()), ex);
        }
    }

    public static String sha256(InputStream input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestStream = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (digestStream.read(buffer) != -1) {
                    // Reading is the point; DigestInputStream updates the digest as it goes.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
        catch (IOException ex) {
            throw new FilingIngestionException("Could not compute content hash", ex);
        }
    }
}
