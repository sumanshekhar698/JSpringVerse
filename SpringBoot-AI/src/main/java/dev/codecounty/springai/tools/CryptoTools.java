package dev.codecounty.springai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class CryptoTools {

    private final RestClient restClient = RestClient.create();

    @Tool(description = "Returns the latest cryptocurrency price in USD")
    public String getCryptoPrice(String coin) {

        String url =
                "https://api.coingecko.com/api/v3/simple/price?ids="
                        + coin.toLowerCase()
                        + "&vs_currencies=usd";

        Map response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);

        return response.toString();
    }
}