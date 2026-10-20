package br.com.caseitau.auth.infrastructure;

import br.com.caseitau.auth.application.AuthorizationUseCase;
import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import br.com.caseitau.auth.application.AuthorizationUseCase.Command;
import br.com.caseitau.auth.domain.Money;
import br.com.caseitau.auth.domain.OperationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.junit.jupiter.api.Assertions.*;

class DynamoAuthorizationIntegrationTest {
    static final GenericContainer<?> dynamodb = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.6.0"))
            .withExposedPorts(8000);
    static DynamoDbClient ddb;
    static DynamoAuthorizationAdapter adapter;
    static final String TABLE = "case-test-authorization";

    @BeforeAll static void setup() {
        dynamodb.start();
        ddb = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:" + dynamodb.getMappedPort(8000)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .region(Region.US_EAST_1).build();
        ddb.createTable(CreateTableRequest.builder().tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        adapter = new DynamoAuthorizationAdapter(ddb, new ObjectMapper(), TABLE);
    }

    @AfterAll static void teardown() {
        if (ddb != null) ddb.close();
        dynamodb.stop();
    }

    static String bootstrap() {
        String contract = "contract-" + UUID.randomUUID();
        ddb.putItem(PutItemRequest.builder().tableName(TABLE).item(Map.of(
                "pk", s("CONTRACT#" + contract), "sk", s("LIMIT"),
                "contractId", s(contract), "accountId", s("account-1"),
                "totalMinorUnits", n(700_000), "availableMinorUnits", n(700_000),
                "reservedMinorUnits", n(0), "usedMinorUnits", n(0), "version", n(0)))
                .build());
        return contract;
    }

    static Command cmd(String contract, String op, String amount, UUID key, String originalId) {
        return new Command(contract, "account-1", new BigDecimal(amount), "BRL",
                op, key, "trace-case", originalId, null, Map.of());
    }

    @Test void concurrentFiveThousandOnSevenThousandOnlyOneCanCommit() {
        String contract = bootstrap();
        CountDownLatch start = new CountDownLatch(1);
        var one = CompletableFuture.supplyAsync(() -> attempt(start, cmd(contract, "RESERVA", "5000.00", UUID.randomUUID(), null)));
        var two = CompletableFuture.supplyAsync(() -> attempt(start, cmd(contract, "RESERVA", "5000.00", UUID.randomUUID(), null)));
        start.countDown();
        assertEquals(1, (one.join() ? 1 : 0) + (two.join() ? 1 : 0),
                "Duas reservas de R$ 5.000 não podem confirmar em R$ 7.000");
        var limit = ddb.getItem(GetItemRequest.builder().tableName(TABLE)
                .key(Map.of("pk", s("CONTRACT#" + contract), "sk", s("LIMIT"))).consistentRead(true).build()).item();
        assertEquals("200000", limit.get("availableMinorUnits").n());
        assertEquals("500000", limit.get("reservedMinorUnits").n());
    }

    @Test void idempotencyReplayNeverCreatesSecondReservation() {
        String contract = bootstrap();
        UUID key = UUID.randomUUID();
        Command request = cmd(contract, "RESERVA", "5000.00", key, null);
        var first = adapter.apply(request, OperationType.RESERVA, Money.of(request.amount(), request.currency()), "testhash");
        var again = adapter.apply(request, OperationType.RESERVA, Money.of(request.amount(), request.currency()), "testhash");
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.authorizationId(), again.authorizationId());
    }

    @Test void estornoOfReservationRestoresLimitOnce() {
        String contract = bootstrap();
        Command request = cmd(contract, "RESERVA", "5000.00", UUID.randomUUID(), null);
        var reserved = adapter.apply(request, OperationType.RESERVA, Money.of(request.amount(), request.currency()), "hash-reservation");
        Command reverse = cmd(contract, "ESTORNO", "5000.00", UUID.randomUUID(), reserved.authorizationId());
        var reversed = adapter.apply(reverse, OperationType.ESTORNO, Money.of(reverse.amount(), reverse.currency()), "hash-reverse");
        assertEquals(700_000L, reversed.availableAfterMinorUnits());
        assertEquals(0L, reversed.reservedAfterMinorUnits());
        Command second = cmd(contract, "ESTORNO", "5000.00", UUID.randomUUID(), reserved.authorizationId());
        assertEquals(409, assertThrows(ApiFailure.class,
                () -> adapter.apply(second, OperationType.ESTORNO, Money.of(second.amount(), second.currency()), "hash2")).status().value());
    }

    static boolean attempt(CountDownLatch start, Command request) {
        try {
            start.await();
            adapter.apply(request, OperationType.RESERVA, Money.of(request.amount(), request.currency()), UUID.randomUUID().toString());
            return true;
        } catch (ApiFailure e) {
            if (e.status().value() != 402 && e.status().value() != 409) throw e;
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new RuntimeException(e);
        }
    }

    static AttributeValue s(String s) {return AttributeValue.builder().s(s).build();}
    static AttributeValue n(long n) {return AttributeValue.builder().n(Long.toString(n)).build();}
}
