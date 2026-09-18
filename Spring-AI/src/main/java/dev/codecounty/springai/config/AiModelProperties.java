package dev.codecounty.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "app.ai")
public record AiModelProperties(
        @DefaultValue("ollama") String defaultProvider,
        @DefaultValue Map<String, Set<String>> providers
) {
    public AiModelProperties {
        if (providers == null) {
            providers = Map.of();
        }
    }

/*    public boolean isModelSupported(String provider, String model) {
        if (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) {
            return true;
        }
        if (provider == null) {
            return false;
        }
        Set<String> models = providers.get(provider.toLowerCase());
        return models != null && models.contains(model.trim().toLowerCase());
    }*/


    /*
     *  Ollama's Implicit Stripping: When you pull a model without specifying a tag (e.g., ollama pull mistral),
     *  Ollama stores it internally as mistral:latest.
     *  However, Ollama's API will accept both "mistral" and "mistral:latest".
     * */
    public boolean isModelSupported(String provider, String model) {
        if (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) {
            return true;
        }
        if (provider == null) {
            return false;
        }
        Set<String> models = providers.get(provider.toLowerCase());
        if (models == null) {
            return false;
        }

        String cleanedModel = model.trim().toLowerCase();
        String baseModel = cleanedModel.endsWith(":latest")
                ? cleanedModel.replace(":latest", "")
                : cleanedModel;

        return models.contains(cleanedModel)
                || models.contains(baseModel)
                || models.contains(baseModel + ":latest");
    }
}