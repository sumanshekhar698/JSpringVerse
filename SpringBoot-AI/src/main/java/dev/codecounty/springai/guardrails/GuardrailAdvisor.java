package dev.codecounty.springai.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Applies {@link GuardrailService} to every call made through a chat client.
 *
 * <p>Runs at the highest precedence so a blocked request short-circuits before retrieval,
 * tool calling or the model call itself — a refused question should cost nothing.
 *
 * <p>Blocking returns a normal, well-formed response containing the refusal text rather
 * than throwing. Callers get a 200 with an explanation instead of an error they have to
 * special-case, and streaming clients see the refusal arrive through the same channel as
 * any other answer.
 */
public class GuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);

    /** Context key set on a blocked response, so controllers can report it without re-checking. */
    public static final String CONTEXT_BLOCKED = "guardrail.blocked";
    public static final String CONTEXT_CATEGORY = "guardrail.category";

    private final GuardrailService guardrailService;
    private final String refusalMessage;
    private final MeterRegistry meterRegistry;

    public GuardrailAdvisor(GuardrailService guardrailService, String refusalMessage,
                            MeterRegistry meterRegistry) {
        this.guardrailService = guardrailService;
        this.refusalMessage = refusalMessage;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "guardrail";
    }

    @Override
    public int getOrder() {
        // Ahead of retrieval, memory and logging advisors.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        GuardrailVerdict verdict = guardrailService.checkInput(lastUserText(request));
        if (verdict.blocked()) {
            return refuse(request, verdict);
        }

        ChatClientResponse response = chain.nextCall(request);
        return withOutputGuardrails(response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        GuardrailVerdict verdict = guardrailService.checkInput(lastUserText(request));
        if (verdict.blocked()) {
            return Flux.from(Mono.just(refuse(request, verdict)));
        }
        // The output disclaimer is not applied to streams: each element carries a fragment,
        // so there is no complete answer to inspect until the stream has already been sent.
        // The system prompt carries the same constraint for streaming callers.
        return chain.nextStream(request);
    }

    private ChatClientResponse refuse(ChatClientRequest request, GuardrailVerdict verdict) {
        log.warn("Guardrail blocked a request [{}]: {}", verdict.category(), verdict.reason());
        if (meterRegistry != null) {
            meterRegistry.counter("jspringverse.guardrail.blocked",
                    "category", verdict.category().name()).increment();
        }

        ChatResponse refusal = new ChatResponse(
                List.of(new Generation(new AssistantMessage(refusalMessage))));

        return ChatClientResponse.builder()
                .chatResponse(refusal)
                .context(CONTEXT_BLOCKED, true)
                .context(CONTEXT_CATEGORY, verdict.category().name())
                .build();
    }

    private ChatClientResponse withOutputGuardrails(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return response;
        }
        Generation generation = response.chatResponse().getResult();
        if (generation == null || generation.getOutput() == null) {
            return response;
        }

        String original = generation.getOutput().getText();
        String guarded = guardrailService.applyOutputGuardrails(original);
        if (guarded == null || guarded.equals(original)) {
            return response;
        }

        ChatResponse rebuilt = ChatResponse.builder()
                .from(response.chatResponse())
                .generations(List.of(new Generation(
                        new AssistantMessage(guarded), generation.getMetadata())))
                .build();

        return response.mutate().chatResponse(rebuilt).build();
    }

    /** The text the user actually asked, ignoring the system prompt and prior turns. */
    private static String lastUserText(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.USER) {
                return message.getText();
            }
        }
        return null;
    }
}
