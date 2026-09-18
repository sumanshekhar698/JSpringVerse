package dev.codecounty.springai.controllers;

import dev.codecounty.springai.dto.ModelMetadataResponse;
import dev.codecounty.springai.service.LlmRouter;
import dev.codecounty.springai.service.ProviderHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/meta")
public class AiMetaController {

    private final LlmRouter llmRouter;
    private final ProviderHealthService healthService;

    public AiMetaController(LlmRouter llmRouter, ProviderHealthService healthService) {
        this.llmRouter = llmRouter;
        this.healthService = healthService;
    }

    @GetMapping("/models")
    public ModelMetadataResponse getAvailableModels() {
        return new ModelMetadataResponse(llmRouter.getAvailableModels());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> report = healthService.checkProviders();

        boolean anyUp = report.values().stream()
                .filter(v -> v instanceof Map<?, ?>)
                .map(v -> ((Map<?, ?>) v).get("status"))
                .anyMatch("UP"::equals);

        return ResponseEntity.status(anyUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(report);
    }
}