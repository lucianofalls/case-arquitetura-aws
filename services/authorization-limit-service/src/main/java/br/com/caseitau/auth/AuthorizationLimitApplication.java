package br.com.caseitau.auth;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication
public class AuthorizationLimitApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthorizationLimitApplication.class, args);
    }

    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${app.aws.region}") String region,
            @Value("${app.ddb.endpoint:}") String endpoint) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
