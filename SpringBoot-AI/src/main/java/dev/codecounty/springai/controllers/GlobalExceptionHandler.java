package dev.codecounty.springai.controllers;

import com.openai.errors.OpenAIException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import dev.codecounty.springai.filings.FilingIngestionException;
import dev.codecounty.springai.market.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Maps application exceptions to RFC 9457 problem responses.
 *
 * <p>Without this, a bad ticker or an unparseable PDF surfaces as an opaque 500 with a
 * stack trace in the body — which both leaks internals and tells the caller nothing
 * actionable.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MarketDataException.SymbolNotFound.class)
    public ProblemDetail handleSymbolNotFound(MarketDataException.SymbolNotFound ex) {
        return problem(HttpStatus.NOT_FOUND, "Symbol not found", ex.getMessage(), "symbol-not-found");
    }

    @ExceptionHandler(MarketDataException.class)
    public ProblemDetail handleMarketData(MarketDataException ex) {
        log.warn("Market data failure: {}", ex.getMessage());
        // The upstream vendor is unavailable or rejected us; that is a gateway problem,
        // not a client error.
        return problem(HttpStatus.BAD_GATEWAY, "Market data unavailable",
                ex.getMessage(), "market-data-unavailable");
    }

    @ExceptionHandler(FilingIngestionException.class)
    public ProblemDetail handleIngestion(FilingIngestionException ex) {
        log.warn("Filing ingestion failure: {}", ex.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Filing could not be ingested",
                ex.getMessage(), "filing-ingestion-failed");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Filing too large",
                "The uploaded file exceeds the configured maximum. "
                        + "Raise spring.servlet.multipart.max-file-size if this is expected.",
                "upload-too-large");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", details, "validation-failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), "invalid-argument");
    }

    /**
     * Failures from the model provider.
     *
     * <p>Two things matter here. First, the provider's own message is <b>never</b> returned
     * to the caller: OpenAI's 401 body echoes a partially-masked API key, and its 400s can
     * quote prompt content. Both belong in the log, not in an HTTP response that may be
     * shown to an end user or captured by a client. Second, the status is mapped so an
     * operator can tell a misconfiguration from a transient outage without opening the logs.
     */
    @ExceptionHandler(OpenAIException.class)
    public ProblemDetail handleModelProvider(OpenAIException ex) {
        log.error("Model provider call failed", ex);

        return switch (ex) {
            case UnauthorizedException ignored -> problem(HttpStatus.BAD_GATEWAY,
                    "Model provider rejected our credentials",
                    "The configured API key was rejected. Check OPENAI_API_KEY.",
                    "model-auth-failed");
            case PermissionDeniedException ignored -> problem(HttpStatus.BAD_GATEWAY,
                    "Model provider denied access",
                    "The API key lacks access to the configured model.",
                    "model-permission-denied");
            case RateLimitException ignored -> problem(HttpStatus.TOO_MANY_REQUESTS,
                    "Model provider rate limit reached",
                    "The request was throttled upstream. Retry shortly.",
                    "model-rate-limited");
            default -> problem(HttpStatus.BAD_GATEWAY,
                    "Model provider unavailable",
                    "The upstream model call failed. See server logs for detail.",
                    "model-call-failed");
        };
    }

    /**
     * Last resort.
     *
     * <p>Without this, anything unanticipated reaches the default error page carrying a
     * stack trace and whatever the exception message happened to contain — which is how the
     * provider's masked API key leaked into a 500 body before this handler existed.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "The request could not be completed. See server logs for detail.", "internal-error");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://jspringverse.dev/problems/" + type));
        return problem;
    }
}
