package dev.codecounty.springai.controllers;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/ollama")
public class OllamaController {

    private final org.springframework.ai.ollama.OllamaChatModel chatModel;

    public OllamaController(org.springframework.ai.ollama.OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @org.springframework.web.bind.annotation.GetMapping("/generate")
    public String generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return chatModel.call(message);
    }

}
