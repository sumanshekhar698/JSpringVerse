package dev.codecounty.springai.controllers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/openai")
public class OpenAIChatController {

    private final ChatClient chatClient;

/*    // By Default, we need ChatClient.Builder instead of ChatClient
    public OpenAIChatController(ChatClient.Builder chatClientBuilder) {

        this.chatClient = chatClientBuilder.build();
    }*/


    public OpenAIChatController(@Qualifier("openAiChatClient") ChatClient openAiChatClient) {
        this.chatClient = openAiChatClient;
    }

    @GetMapping("/chat")
    public String generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        CallResponseSpec responseSpec = chatClient.prompt(message).call();
        String content = responseSpec.content();
        return content;
    }

}
