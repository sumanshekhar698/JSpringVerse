package dev.codecounty.springai.controllers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/ollama")
public class OllamaChatController {

    private final ChatClient chatClient;


    public OllamaChatController(@Qualifier("ollamaChatClient") ChatClient ollamaChatClient) {
        this.chatClient = ollamaChatClient;
    }

    @GetMapping("/chat")
    public String generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        ChatClient.CallResponseSpec responseSpec = chatClient.prompt(message).call();
        return responseSpec.content();
    }

}
