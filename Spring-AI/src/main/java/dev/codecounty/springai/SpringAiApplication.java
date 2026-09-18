package dev.codecounty.springai;

import dev.codecounty.springai.config.AiModelProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

//@EnableConfigurationProperties(AiModelProperties.class)
@ConfigurationPropertiesScan// This annotation is used to scan for classes annotated with @ConfigurationProperties
@SpringBootApplication
public class SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }

}
