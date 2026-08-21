package dev.codecounty.springai.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.codecounty.springai.guardrails.GuardrailAdvisor;
import dev.codecounty.springai.memory.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Entry point for the stock research agent.
 *
 * <p>POST rather than GET: questions can be long, and putting them in a query string leaks
 * them into access logs and browser history.
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent")
public class StockAgentController {

    private static final Logger log = LoggerFactory.getLogger(StockAgentController.class);

    private final ChatClient agentChatClient;
    private final ConversationService conversationService;

    public StockAgentController(
            @Qualifier("stockAgentChatClient") ChatClient agentChatClient,
            ConversationService conversationService) {
        this.agentChatClient = agentChatClient;
        this.conversationService = conversationService;
    }

    @Schema(description = "A question about a US-listed company.")
    public record AgentRequest(
            @Schema(
                    description = "Natural-language question. May span market data and filings.",
                    example = "What is Nvidia trading at, and what supply chain risks did their latest 10-K disclose?")
            @NotBlank(message = "question must not be blank")
            @Size(max = 4000, message = "question must be at most 4000 characters")
            String question,

            @Schema(
                    description = "Continues an existing conversation. Omit to start a new one — "
                            + "the response returns the id to reuse on follow-ups.",
                    example = "3f1c8a52-9b0e-4a77-8c31-2d5e6f7a8b90",
                    nullable = true)
            String conversationId) {
    }

    /**
     * @param guardrailBlocked  true when a guardrail refused the question and no model call
     *                          was made — the caller can distinguish a refusal from an answer
     *                          without parsing the prose
     * @param guardrailCategory which guardrail tripped, or null
     */
    public record AgentResponse(
            @Schema(description = "The agent's answer, or the refusal text when a guardrail blocked the question.")
            String answer,
            @Schema(description = "Pass this back as conversationId on the next request to continue the thread.")
            String conversationId,
            @Schema(description = "Server-side turn duration. Near-zero when a guardrail short-circuited.", example = "3412")
            long elapsedMillis,
            @Schema(description = "True when a guardrail refused the question and no model call was made.", example = "false")
            boolean guardrailBlocked,
            @Schema(description = "Which guardrail tripped, or null.",
                    allowableValues = {"EMPTY_INPUT", "INPUT_TOO_LONG", "PROMPT_INJECTION", "INVESTMENT_ADVICE"},
                    nullable = true)
            String guardrailCategory) {
    }

    @Operation(
            summary = "Ask the research agent",
            description = """
                    The model chooses which tools to call: live quotes, price history, symbol \
                    lookup, and filing search. Filing claims come back cited.

                    A guardrail refusal returns **200** with `guardrailBlocked: true`, not a 4xx — \
                    check that flag rather than the status code.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Answered, or refused by a guardrail — distinguish via `guardrailBlocked`."),
            @ApiResponse(responseCode = "400", description = "Blank or oversized question.",
                    content = @Content(mediaType = "application/problem+json")),
            @ApiResponse(responseCode = "429", description = "Model provider rate limit reached.",
                    content = @Content(mediaType = "application/problem+json")),
            @ApiResponse(responseCode = "502", description = "Model provider or market feed unavailable.",
                    content = @Content(mediaType = "application/problem+json"))})
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AgentResponse ask(@Valid @RequestBody AgentRequest request) {
        Instant startedAt = Instant.now();
        String conversationId = conversationService.resolveConversationId(request.conversationId());
        log.info("Agent question [conversation {}]: {}", conversationId, request.question());

        ChatClientResponse response = agentChatClient.prompt()
                .user(request.question())
                // The memory advisor reads this to decide which transcript to replay and
                // which to append to. Without it every request would share one conversation.
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        String answer = response.chatResponse() == null ? null
                : response.chatResponse().getResult().getOutput().getText();

        boolean blocked = Boolean.TRUE.equals(response.context().get(GuardrailAdvisor.CONTEXT_BLOCKED));
        String category = (String) response.context().get(GuardrailAdvisor.CONTEXT_CATEGORY);

        return new AgentResponse(answer, conversationId,
                Duration.between(startedAt, Instant.now()).toMillis(), blocked, category);
    }

    /**
     * Streaming variant.
     *
     * <p>Agent turns that chain several tool calls take many seconds; streaming the tokens
     * keeps a UI responsive instead of showing a spinner for the whole turn.
     */
    @Operation(
            summary = "Ask the research agent (streaming)",
            description = """
                    Server-sent events, one element per token fragment. Use for interactive UIs: \
                    a turn chaining several tool calls takes many seconds.

                    Note the output disclaimer is **not** appended on this path — each element \
                    carries a fragment, so there is no complete answer to inspect before it is \
                    sent. Input guardrails still apply, and a refusal arrives as the stream body.""")
    @ApiResponse(responseCode = "200", description = "Token stream.",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping(value = "/ask/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStreaming(@Valid @RequestBody AgentRequest request) {
        String conversationId = conversationService.resolveConversationId(request.conversationId());
        log.info("Agent question (streaming) [conversation {}]: {}", conversationId, request.question());
        return agentChatClient.prompt()
                .user(request.question())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    // --- Conversation management --------------------------------------------------------

    public record TranscriptEntry(String role, String content) {
    }

    public record Transcript(String conversationId, int messageCount, List<TranscriptEntry> messages) {
    }

    @Operation(
            summary = "Read a conversation transcript",
            description = """
                    The retained window for a conversation, oldest first — what would actually \
                    be replayed into the prompt on the next turn.

                    Only the last `jspringverse.memory.max-messages` (40) are kept; older ones \
                    are dropped, so this is not a complete audit log.""")
    @GetMapping("/conversations/{conversationId}")
    public Transcript transcript(
            @Parameter(description = "The id returned by /ask") @PathVariable String conversationId) {

        String resolved = conversationService.resolveConversationId(conversationId);
        List<Message> messages = conversationService.transcript(resolved);

        return new Transcript(resolved, messages.size(), messages.stream()
                .map(message -> new TranscriptEntry(
                        message.getMessageType().getValue(), message.getText()))
                .toList());
    }

    @Operation(
            summary = "List conversation ids",
            description = """
                    Every conversation with retained messages.

                    **No authorisation is enforced.** Conversation ids are the only thing \
                    separating one caller's history from another's, so this endpoint exposes \
                    every id on the instance. Remove it or put it behind authentication before \
                    this is reachable by more than one user.""")
    @GetMapping("/conversations")
    public List<String> conversations() {
        return conversationService.conversationIds();
    }

    @Operation(
            summary = "Forget a conversation",
            description = "Deletes the stored transcript. Irreversible. The next request with "
                    + "the same id starts fresh.")
    @ApiResponse(responseCode = "204", description = "Cleared.")
    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String conversationId) {
        conversationService.clear(conversationService.resolveConversationId(conversationId));
    }
}
