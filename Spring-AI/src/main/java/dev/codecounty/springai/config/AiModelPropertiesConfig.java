package dev.codecounty.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Deprecated
@Configuration
@ConfigurationProperties(prefix = "app.ai")
public class AiModelPropertiesConfig {

    private String defaultProvider = "ollama";
    private Map<String, Set<String>> providers = new HashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, Set<String>> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Set<String>> providers) {
        this.providers = providers;
    }

    public boolean isModelSupported(String provider, String model) {
        if (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) {
            return true;
        }
        Set<String> models = providers.get(provider.toLowerCase());
        return models != null && models.contains(model.trim().toLowerCase());
    }
}