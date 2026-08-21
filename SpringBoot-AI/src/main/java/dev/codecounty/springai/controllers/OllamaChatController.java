package dev.codecounty.springai.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/ollama")
@Tag(name = "Sandbox")
public class OllamaChatController {

    private final ChatClient chatClient;

    public OllamaChatController(@Qualifier("ollamaChatClient") ChatClient ollamaChatClient) {
        this.chatClient = ollamaChatClient;
    }

    @Operation(
            summary = "Local-model chat sandbox",
            description = "Tool-calling playground against a locally hosted Ollama model, with a "
                    + "crypto price tool attached. Useful for checking that a tool's description and "
                    + "schema are clear enough to be called correctly without paying for hosted "
                    + "inference. Not part of the stock agent. Requires a running Ollama daemon.")
    @GetMapping("/chat")
    public String generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        ChatClient.CallResponseSpec responseSpec = chatClient.prompt(message).call();
        return responseSpec.content();
    }

}
