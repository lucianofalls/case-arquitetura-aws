package br.com.caseitau.accounting;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Production uses default AWS endpoints; local profile overrides through env only. */
@SpringBootApplication
@EnableScheduling
public class AccountingApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountingApplication.class, args);
    }

    @Bean
    DynamoDbClient dynamo(
            @Value("${app.aws.region}") String region,
            @Value("${app.ddb.endpoint:}") String endpoint) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    SqsClient sqs(
            @Value("${app.aws.region}") String region,
            @Value("${app.sqs.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
