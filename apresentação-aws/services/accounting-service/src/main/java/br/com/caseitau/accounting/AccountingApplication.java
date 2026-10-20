package br.com.caseitau.accounting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@SpringBootApplication
@EnableScheduling
public class AccountingApplication {
    public static void main(String[] args) { SpringApplication.run(AccountingApplication.class, args); }

    @Bean DynamoDbClient dynamo(@Value("${app.aws.region}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean SqsClient sqs(@Value("${app.aws.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }
}
