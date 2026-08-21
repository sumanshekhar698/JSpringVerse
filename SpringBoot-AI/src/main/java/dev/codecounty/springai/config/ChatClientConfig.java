package dev.codecounty.springai.config;

import dev.codecounty.springai.tools.CryptoTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local-model sandbox, kept separate from the production agent in {@link StockAgentConfig}.
 *
 * <p>Exercises tool calling against a locally hosted Ollama model, which is useful for
 * checking that a tool's description and schema are clear enough to be called correctly
 * without paying for hosted inference on every iteration.
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel, CryptoTools cryptoTools) {
        return ChatClient.builder(ollamaChatModel)
                .defaultTools(cryptoTools)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
