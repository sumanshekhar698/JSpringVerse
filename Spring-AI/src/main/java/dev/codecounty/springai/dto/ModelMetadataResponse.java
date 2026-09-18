package dev.codecounty.springai.dto;

import java.util.Map;
import java.util.Set;

public record ModelMetadataResponse(
        Map<String, Set<String>> providers
) {}