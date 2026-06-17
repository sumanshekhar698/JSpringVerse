package dev.codecounty.springai.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/ollama")
public class OllamaChatController {

    private final org.springframework.ai.ollama.OllamaChatModel chatModel;

    public OllamaChatController(org.springframework.ai.ollama.OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/chat")
    public String generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return chatModel.call(message);
    }

}
