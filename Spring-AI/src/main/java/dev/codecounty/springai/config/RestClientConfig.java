package dev.codecounty.springai.config;

//import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class RestClientConfig {


    /*Because Spring AI uses Spring 6's RestClient / WebClient under the hood,
     the standard and version-agnostic way to set read/connect timeouts across all AI calls
      is to provide a RestClientCustomizer bean in your configuration:*/
//    @Bean
//    public RestClientCustomizer restClientCustomizer() {
//        return restClientBuilder -> restClientBuilder
//                .requestFactory(new SimpleClientHttpRequestFactory() {{
//                    setConnectTimeout((int) Duration.ofSeconds(60).toMillis());
//                    setReadTimeout((int) Duration.ofMinutes(5).toMillis());
//                }});
//    }


    @Bean
    public RestClientCustomizer restClientCustomizer() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(Duration.ofSeconds(60));
        requestFactory.setReadTimeout(Duration.ofMinutes(5));

        return restClientBuilder -> restClientBuilder.requestFactory(requestFactory);
    }
}