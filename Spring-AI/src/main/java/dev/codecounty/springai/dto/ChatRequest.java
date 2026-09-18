package dev.codecounty.springai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "Message prompt cannot be blank")
        @Size(max = 10000, message = "Message exceeds maximum allowed length")
        String message,

        String provider,

        String model
) {

        /*
        * Instead of littering .trim() or .toLowerCase() checks across your service layer,
        *  you can sanitize strings inside the record's compact constructor:
        *
        * */
        public ChatRequest {
                message = (message != null) ? message.trim() : null;
                provider = (provider != null && !provider.isBlank()) ? provider.trim().toLowerCase() : null;
                model = (model != null && !model.isBlank()) ? model.trim() : null;
        }
}