package dev.codecounty.springai.controllers;

import dev.codecounty.springai.dto.ChatRequest;
import dev.codecounty.springai.dto.ModelMetadataResponse;
import dev.codecounty.springai.service.LlmRouter;
import dev.codecounty.springai.service.ProviderHealthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private final LlmRouter llmRouter;


    public ChatController(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;

    }

    @PostMapping("/chat")
    public String chat(@Valid @RequestBody ChatRequest request) {
        return llmRouter.generate(request);
    }


}
