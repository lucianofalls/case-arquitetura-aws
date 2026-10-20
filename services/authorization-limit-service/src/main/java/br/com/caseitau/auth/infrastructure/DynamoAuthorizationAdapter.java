package br.com.caseitau.auth.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.caseitau.auth.application.AuthorizationUseCase;
import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import br.com.caseitau.auth.application.AuthorizationUseCase.Command;
import br.com.caseitau.auth.application.AuthorizationUseCase.Receipt;
import br.com.caseitau.auth.application.AuthorizationUseCase.AuthorizationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Financial consistency boundary. All financial changes are confirmed by ONE
 * DynamoDB TransactWriteItems request. DynamoDB Streams must be configured
 * independently; this adapter does NOT call EventBridge directly.
 *
 * MVP: authorization with RESERVA only. No financial ledger postings.
 */
@Repository
public final class DynamoAuthorizationAdapter implements AuthorizationStore {
    private final DynamoDbClient dynamo;
    private final ObjectMapper json;
    private final String table;

    public DynamoAuthorizationAdapter(DynamoDbClient dynamo, ObjectMapper json,
                                      @Value("${app.ddb.table}") String table) {
        this.dynamo = dynamo;
        this.json = json;
        this.table = table;
    }

    @Override
    public Receipt reserve(Command command, long amountMinorUnits, String payloadHash) {
        String idemPk = "IDEMPOTENCY#" + command.idempotencyKey();
        Map<String, AttributeValue> existing = read(idemPk, "REQUEST");
        if (!existing.isEmpty()) {
            return replay(existing, payloadHash);
        }

        // Optimistic concurrency, including the exact resulting balance in the
        // persisted replay response; bounded retries for independent requests.
        for (int attempt = 0; attempt < 3; attempt++) {
            var limit = read("CONTRACT#" + command.contractId(), "LIMIT");
            if (limit.isEmpty()) {
                throw new ApiFailure(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", "Contrato não carregado na plataforma");
            }
            long available = number(limit, "availableMinorUnits");
            long reserved = number(limit, "reservedMinorUnits");
            long version = number(limit, "version");

            if (available < amountMinorUnits) {
                // TODO financial spec: store idempotent declines as well.
                throw new ApiFailure(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_LIMIT", "Limite insuficiente");
            }

            final long newAvailable;
            final long newReserved;
            try {
                newAvailable = Math.subtractExact(available, amountMinorUnits);
                newReserved = Math.addExact(reserved, amountMinorUnits);
            } catch (ArithmeticException overflow) {
                throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_OVERFLOW", "Overflow de limite");
            }

            String authId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            String occurredAt = Instant.now().toString();
            String payload = cloudEvent(command, authId, eventId, occurredAt,
                    amountMinorUnits, newReserved);
            var limitUpdate = Update.builder()
                    .tableName(table)
                    .key(key("CONTRACT#" + command.contractId(), "LIMIT"))
                    .conditionExpression("attribute_exists(pk) AND #v = :version AND #available >= :amount")
                    .updateExpression("SET #available = #available - :amount, " +
                            "reservedMinorUnits = reservedMinorUnits + :amount, #v = #v + :one")
                    .expressionAttributeNames(Map.of("#v", "version", "#available", "availableMinorUnits"))
                    .expressionAttributeValues(Map.of(
                            ":version", n(version), ":amount", n(amountMinorUnits), ":one", n(1)))
                    .build();

            var authorization = Put.builder().tableName(table).item(Map.of(
                    "pk", s("AUTH#" + authId), "sk", s("META"), "entityType", s("AUTH"),
                    "authorizationId", s(authId), "contractId", s(command.contractId()),
                    "accountId", s(command.accountId()), "amountMinorUnits", n(amountMinorUnits),
                    "currency", s("BRL"), "reservationState", s("RESERVED"),
                    "reservedAfterMinorUnits", n(newReserved),
                    "createdAt", s(occurredAt)))
                    .conditionExpression("attribute_not_exists(pk)").build();

            var idem = Put.builder().tableName(table).item(Map.of(
                    "pk", s(idemPk), "sk", s("REQUEST"), "entityType", s("IDEMPOTENCY"),
                    "payloadHash", s(payloadHash), "authorizationId", s(authId),
                    "reservedAfterMinorUnits", n(newReserved),
                    "availableAfterMinorUnits", n(newAvailable),
                    "operation", s("RESERVA")))
                    .conditionExpression("attribute_not_exists(pk)").build();

            var outbox = Put.builder().tableName(table).item(Map.of(
                    "pk", s("OUTBOX#" + eventId), "sk", s("EVENT"), "entityType", s("OUTBOX"),
                    "eventId", s(eventId), "eventType", s("TransacaoAutorizada"),
                    "occurredAt", s(occurredAt), "payload", s(payload),
                    "contractId", s(command.contractId())))
                    .conditionExpression("attribute_not_exists(pk)").build();

            try {
                dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                        .clientRequestToken(UUID.randomUUID().toString())
                        .transactItems(List.of(
                                TransactWriteItem.builder().update(limitUpdate).build(),
                                TransactWriteItem.builder().put(authorization).build(),
                                TransactWriteItem.builder().put(idem).build(),
                                TransactWriteItem.builder().put(outbox).build()))
                        .build());
                return new Receipt(authId, newReserved, newAvailable, false);
            } catch (TransactionCanceledException cancelled) {
                // Same key in another request? Return original result, not a new reservation.
                var currentIdem = read(idemPk, "REQUEST");
                if (!currentIdem.isEmpty()) {
                    return replay(currentIdem, payloadHash);
                }
                // Could be version contention, a depleted limit or a transient
                // transaction conflict. Re-read on next attempt.
                if (attempt == 2) {
                    var latest = read("CONTRACT#" + command.contractId(), "LIMIT");
                    if (!latest.isEmpty() && number(latest, "availableMinorUnits") < amountMinorUnits) {
                        throw new ApiFailure(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_LIMIT", "Limite insuficiente após concorrência");
                    }
                    throw new ApiFailure(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Conflito de atualização; tente novamente");
                }
            }
        }
        throw new ApiFailure(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Tentativas de concorrência esgotadas");
    }

    private Receipt replay(Map<String, AttributeValue> item, String payloadHash) {
        if (!text(item, "payloadHash").equals(payloadHash)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key já utilizada com payload diferente");
        }
        return new Receipt(text(item, "authorizationId"),
                number(item, "reservedAfterMinorUnits"),
                number(item, "availableAfterMinorUnits"), true);
    }

    private String cloudEvent(Command cmd, String authId, String eventId, String occurredAt,
                              long amountMinorUnits, long newReserved) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_id", eventId);
        data.put("event_type", "TransacaoAutorizada");
        data.put("event_version", "1.0");
        data.put("occurred_at", occurredAt);
        data.put("id_autorizacao", authId);
        data.put("id_contrato", cmd.contractId());
        data.put("valor", BigDecimal.valueOf(amountMinorUnits, 2).toPlainString());
        data.put("saldo_reservado", BigDecimal.valueOf(newReserved, 2).toPlainString());
        data.put("correlation_id", cmd.correlationId());
        Map<String, Object> cloudEvent = new LinkedHashMap<>();
        cloudEvent.put("specversion", "1.0");
        cloudEvent.put("id", eventId);
        cloudEvent.put("type", "TransacaoAutorizada");
        cloudEvent.put("source", "/authorization-limit-service");
        cloudEvent.put("time", occurredAt);
        cloudEvent.put("datacontenttype", "application/json");
        cloudEvent.put("data", data);
        try {
            return json.writeValueAsString(cloudEvent);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Falha ao serializar evento", ex);
        }
    }

    private Map<String, AttributeValue> read(String pk, String sk) {
        return dynamo.getItem(GetItemRequest.builder().tableName(table)
                .key(key(pk, sk)).consistentRead(true).build()).item();
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("pk", s(pk), "sk", s(sk));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }

    private static long number(Map<String, AttributeValue> item, String key) {
        if (!item.containsKey(key) || item.get(key).n() == null) {
            throw new IllegalStateException("Item sem atributo numérico: " + key);
        }
        return Long.parseLong(item.get(key).n());
    }

    private static String text(Map<String, AttributeValue> item, String key) {
        if (!item.containsKey(key) || item.get(key).s() == null) {
            throw new IllegalStateException("Item sem atributo string: " + key);
        }
        return item.get(key).s();
    }
}
