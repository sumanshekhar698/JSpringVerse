package dev.codecounty.springai.filings;

/** Raised when a filing cannot be parsed, chunked or stored. */
public class FilingIngestionException extends RuntimeException {

    public FilingIngestionException(String message) {
        super(message);
    }

    public FilingIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
