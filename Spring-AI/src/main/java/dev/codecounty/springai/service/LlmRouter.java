package dev.codecounty.springai.service;


import dev.codecounty.springai.config.AiModelProperties;
import dev.codecounty.springai.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private static final String DEFAULT_PROVIDER = "ollama";
    private final Map<String, ChatClient> clients;
    /*    private static final Map<String, Set<String>> SUPPORTED_MODELS = Map.of(
                "ollama", Set.of("qwen2.5-coder:7b", "qwen2.5-coder:14b", "llama3", "llama3.2", "mistral"),
                "openai", Set.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
        );*/
    private final AiModelProperties modelProperties;

    public LlmRouter(
            @Qualifier("ollamaChatClient") ChatClient ollamaChatClient,
            @Qualifier("openAiChatClient") ChatClient openAiChatClient,
            AiModelProperties modelProperties
    ) {
        this.clients = Map.of(
                "ollama", ollamaChatClient,
                "openai", openAiChatClient
        );
        this.modelProperties = modelProperties;
    }

    public Map<String, Set<String>> getAvailableModels() {
        return modelProperties.providers();
    }

/*    private void validateModelCompatibility(String provider, String model) {
        if (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) {
            return; // Skip if user relies on the configured default model
        }

        Set<String> validModels = SUPPORTED_MODELS.get(provider);

        if (validModels == null || !validModels.contains(model.trim().toLowerCase())) {
            throw new IllegalArgumentException(String.format(
                    "Model '%s' is not supported by provider '%s'. Supported models: %s",
                    model, provider, validModels != null ? validModels : Set.of()
            ));
        }
    }*/

    private void validateModelCompatibility(String provider, String model) {
        if (!modelProperties.isModelSupported(provider, model)) {
            Set<String> supported = modelProperties.providers().getOrDefault(provider, Set.of());
            throw new IllegalArgumentException(String.format(
                    "Model '%s' is not supported by provider '%s'. Supported models: %s",
                    model, provider, supported
            ));
        }
    }

//    Option A: Always fallback to default safely
/*    public ChatClient resolveClient(String provider) {
        String key = (provider != null) ? provider.trim().toLowerCase() : DEFAULT_PROVIDER;
        ChatClient client = clients.get(key);
        if (client == null) {
            return clients.get(DEFAULT_PROVIDER);
        }
        return client;
    }*/



/*    public ChatClient resolveClient(String provider) {
        return clients.getOrDefault(
                provider != null ? provider.trim().toLowerCase() : DEFAULT_PROVIDER,
                clients.get(DEFAULT_PROVIDER)
        );
    }*/

    //    Option B: Fail fast with a clear error (Best practice for APIs)
    public ChatClient resolveClient(String provider) {
//        String key = (provider != null && !provider.isBlank())
//                ? provider.trim().toLowerCase()
//                : DEFAULT_PROVIDER;
//        ChatClient client = clients.get(key);

        // Skipping the null-check-in provider because it's being handled in the generate method before calling this method.
        ChatClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("Unsupported AI provider: " + provider +
                    ". Supported providers are: " + clients.keySet());
        }
        return client;
    }

    public String generate(ChatRequest request) {
        String resolvedProvider = (request.provider() != null && !request.provider().isBlank())
                ? request.provider().trim().toLowerCase()
                : DEFAULT_PROVIDER;

        String resolvedModel = (request.model() != null && !request.model().isBlank())
                ? request.model().trim()
                : "default";


        // Set MDC first so validation errors are traceable
        MDC.put("requestId", UUID.randomUUID().toString().substring(0, 8));
        MDC.put("provider", resolvedProvider);
        MDC.put("model", resolvedModel);

        try {
            // Fail-fast if mismatch occurs
            validateModelCompatibility(resolvedProvider, resolvedModel);

            log.info("Dispatching prompt execution");
            ChatClient client = resolveClient(resolvedProvider);

            var promptSpec = client.prompt().user(request.message());

        /*
            If the model is null or blank, your code skips setting promptSpec.options(...).
            When that happens, Spring AI automatically falls back to the default model
            configured in your application.yml (or the underlying provider's default).
          */

            if (!"default".equals(resolvedModel)) {
                // promptSpec.options(ChatOptions.builder().model(model).build()); //Changed in Spring 2.x.x
                promptSpec.options(ChatOptions.builder().model(resolvedModel));
            }

            String response = promptSpec.call().content();
            log.info("Prompt generation completed successfully");
            return response;
        } catch (IllegalArgumentException e) {
            // Let validation errors pass through as-is for a 400 Bad Request
            throw e;
        } catch (Exception e) {
            log.error("Error during prompt generation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate response from the AI provider", e);
        } finally {
            // Clear MDC context to avoid leaking information across requests
            // MDC.clear() wipes out all contextual key-value pairs stored on the current execution thread.
            MDC.clear();
        }
    }
}