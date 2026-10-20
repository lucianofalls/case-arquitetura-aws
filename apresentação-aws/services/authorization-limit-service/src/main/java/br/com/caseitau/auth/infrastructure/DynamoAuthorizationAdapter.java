package br.com.caseitau.auth.infrastructure;

import br.com.caseitau.auth.application.AuthorizationUseCase;
import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import br.com.caseitau.auth.application.AuthorizationUseCase.Command;
import br.com.caseitau.auth.application.AuthorizationUseCase.Receipt;
import br.com.caseitau.auth.domain.Authorization;
import br.com.caseitau.auth.domain.ContractLimit;
import br.com.caseitau.auth.domain.Money;
import br.com.caseitau.auth.domain.OperationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Outbound adapter. No business code calls AWS directly.
 * Single contract's aggregate lives in one LIMIT item and is always changed
 * with an atomic, conditional DynamoDB transaction.
 */
@Repository
public final class DynamoAuthorizationAdapter implements AuthorizationUseCase.AuthorizationStore {
    private static final int MAX_ATTEMPTS = 5;
    private final DynamoDbClient ddb;
    private final ObjectMapper mapper;
    private final String table;

    public DynamoAuthorizationAdapter(DynamoDbClient ddb, ObjectMapper mapper,
                                      @Value("${app.ddb.table}") String table) {
        this.ddb = ddb; this.mapper = mapper; this.table = table;
    }

    @Override
    public Receipt apply(Command command, OperationType operation, Money amount, String payloadHash) {
        final String idemPk = "IDEMPOTENCY#" + command.idempotencyKey();
        var idem = read(idemPk, "REQUEST");
        if (!idem.isEmpty()) return replay(idem, payloadHash);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            idem = read(idemPk, "REQUEST");
            if (!idem.isEmpty()) return replay(idem, payloadHash);

            var limitItem = read("CONTRACT#" + command.contractId(), "LIMIT");
            if (limitItem.isEmpty()) {
                throw new ApiFailure(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", "Contrato não encontrado");
            }
            ContractLimit before = fromLimit(limitItem);
            if (!before.accountId().equals(command.accountId())) {
                throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_MISMATCH", "Conta não corresponde ao contrato");
            }

            Authorization original = null;
            if (operation == OperationType.ESTORNO) {
                var originalItem = read("AUTH#" + command.originalAuthorizationId(), "META");
                if (originalItem.isEmpty()) {
                    throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ORIGINAL_NOT_FOUND", "Autorização original não existe");
                }
                original = fromAuth(originalItem);
                if (!original.contractId().equals(command.contractId())
                        || original.amountMinorUnits() != amount.cents()
                        || !originalItem.get("accountId").s().equals(command.accountId())) {
                    throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ORIGINAL_MISMATCH",
                            "Estorno deve corresponder a contrato, conta e valor originais");
                }
                if (original.reversed()) {
                    throw new ApiFailure(HttpStatus.CONFLICT, "ALREADY_REVERSED", "Autorização já estornada");
                }
            }

            final ContractLimit next;
            try {
                next = switch (operation) {
                    case RESERVA -> before.reserve(amount.cents());
                    case DEBITO -> before.debit(amount.cents());
                    case CREDITO -> before.credit(amount.cents());
                    case ESTORNO -> before.reverse(original.type(), amount.cents());
                };
            } catch (ContractLimit.InsufficientLimit insufficient) {
                persistDecline(idemPk, payloadHash, "INSUFFICIENT_LIMIT");
                throw new ApiFailure(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_LIMIT", "Limite insuficiente");
            } catch (ContractLimit.InvalidTransition invalid) {
                throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSITION", invalid.getMessage());
            }

            String authId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            String instant = Instant.now().toString();
            String eventType = switch (operation) {
                case RESERVA, DEBITO -> "TransacaoAutorizada";
                case CREDITO -> "LimiteAlterado";
                case ESTORNO -> "TransacaoEstornada";
            };

            var updateLimit = Update.builder()
                    .tableName(table)
                    .key(key("CONTRACT#" + command.contractId(), "LIMIT"))
                    .conditionExpression("attribute_exists(pk) AND #v = :oldVersion AND #available = :oldAvailable")
                    .updateExpression("SET #available = :newAvailable, #reserved = :newReserved, " +
                            "#used = :newUsed, #v = :newVersion")
                    .expressionAttributeNames(Map.of(
                            "#v", "version", "#available", "availableMinorUnits",
                            "#reserved", "reservedMinorUnits", "#used", "usedMinorUnits"))
                    .expressionAttributeValues(Map.of(
                            ":oldVersion", n(before.version()),
                            ":oldAvailable", n(before.available()),
                            ":newAvailable", n(next.available()),
                            ":newReserved", n(next.reserved()),
                            ":newUsed", n(next.used()),
                            ":newVersion", n(next.version())))
                    .build();

            Map<String,AttributeValue> authItem = new HashMap<>();
            authItem.put("pk", s("AUTH#" + authId)); authItem.put("sk", s("META"));
            authItem.put("entityType", s("AUTH")); authItem.put("authorizationId", s(authId));
            authItem.put("contractId", s(command.contractId())); authItem.put("accountId", s(command.accountId()));
            authItem.put("operation", s(operation.name())); authItem.put("amountMinorUnits", n(amount.cents()));
            authItem.put("reservedAfterMinorUnits", n(next.reserved())); authItem.put("availableAfterMinorUnits", n(next.available()));
            authItem.put("reversed", bool(false)); authItem.put("createdAt", s(instant));
            authItem.put("status", s(operation == OperationType.ESTORNO ? "REVERSED" : "APPROVED"));
            if (original != null) authItem.put("originalAuthorizationId", s(original.id()));
            var putAuth = putNew(authItem);

            Map<String,AttributeValue> idemItem = new HashMap<>();
            idemItem.put("pk", s(idemPk)); idemItem.put("sk", s("REQUEST"));
            idemItem.put("entityType", s("IDEMPOTENCY")); idemItem.put("payloadHash", s(payloadHash));
            idemItem.put("authorizationId", s(authId)); idemItem.put("outcome", s("APPROVED"));
            idemItem.put("operation", s(operation.name()));
            idemItem.put("reservedAfterMinorUnits", n(next.reserved()));
            idemItem.put("availableAfterMinorUnits", n(next.available()));
            var putIdem = putNew(idemItem);

            Map<String,AttributeValue> outbox = new HashMap<>();
            outbox.put("pk", s("OUTBOX#" + eventId)); outbox.put("sk", s("EVENT"));
            outbox.put("entityType", s("OUTBOX")); outbox.put("eventId", s(eventId));
            outbox.put("eventType", s(eventType)); outbox.put("eventVersion", s("1.0"));
            outbox.put("occurredAt", s(instant));
            outbox.put("authorizationId", s(authId));
            outbox.put("contractId", s(command.contractId()));
            outbox.put("accountId", s(command.accountId()));
            outbox.put("operation", s(operation.name()));
            outbox.put("amountDecimal", s(amount.decimal().toPlainString()));
            outbox.put("reservedDecimal", s(MoneyDecimal.toString(next.reserved())));
            outbox.put("correlationId", s(command.correlationId()));
            outbox.put("eventPayload", s(eventJson(eventId, eventType, command, authId, amount, next, instant)));
            var putEvent = putNew(outbox);

            List<TransactWriteItem> changes = new ArrayList<>();
            changes.add(TransactWriteItem.builder().update(updateLimit).build());
            changes.add(TransactWriteItem.builder().put(putAuth).build());
            changes.add(TransactWriteItem.builder().put(putIdem).build());
            changes.add(TransactWriteItem.builder().put(putEvent).build());
            if (original != null) {
                changes.add(TransactWriteItem.builder().update(Update.builder()
                        .tableName(table).key(key("AUTH#" + original.id(), "META"))
                        .conditionExpression("attribute_exists(pk) AND reversed = :false")
                        .updateExpression("SET reversed = :true, reversedBy = :reversal")
                        .expressionAttributeValues(Map.of(":false", bool(false), ":true", bool(true),
                                ":reversal", s(authId)))
                        .build()).build());
            }
            try {
                ddb.transactWriteItems(TransactWriteItemsRequest.builder()
                        .clientRequestToken(UUID.randomUUID().toString())
                        .transactItems(changes).build());
                return new Receipt(authId, next.reserved(), next.available(), false, operation);
            } catch (TransactionCanceledException conflict) {
                var completed = read(idemPk, "REQUEST");
                if (!completed.isEmpty()) return replay(completed, payloadHash);
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw new ApiFailure(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Conflito de escrita no contrato");
                }
                // Stale version or original authorization reversed by another request.
                // Next iteration re-reads the entire aggregate and original state.
            }
        }
        throw new ApiFailure(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Número máximo de tentativas atingido");
    }

    private void persistDecline(String idemPk, String requestHash, String reason) {
        try {
            ddb.putItem(PutItemRequest.builder().tableName(table)
                    .item(Map.of(
                            "pk", s(idemPk), "sk", s("REQUEST"),
                            "entityType", s("IDEMPOTENCY"), "payloadHash", s(requestHash),
                            "outcome", s("DECLINED"), "reason", s(reason)))
                    .conditionExpression("attribute_not_exists(pk)").build());
        } catch (ConditionalCheckFailedException alreadyStored) {
            // Only concurrent same-key writers can cause this; retry path returns
            // the exact existing result on the next HTTP call.
            var stored = read(idemPk, "REQUEST");
            if (!stored.isEmpty()) replay(stored, requestHash);
        }
    }

    private Receipt replay(Map<String,AttributeValue> item, String hash) {
        if (!txt(item,"payloadHash").equals(hash)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "A mesma chave foi utilizada com outro payload");
        }
        if ("DECLINED".equals(txt(item,"outcome"))) {
            throw new ApiFailure(HttpStatus.PAYMENT_REQUIRED, txt(item,"reason"), "Operação previamente recusada");
        }
        return new Receipt(txt(item, "authorizationId"),
                number(item, "reservedAfterMinorUnits"), number(item, "availableAfterMinorUnits"),
                true, OperationType.valueOf(txt(item, "operation")));
    }

    private Map<String,AttributeValue> read(String pk, String sk) {
        return ddb.getItem(GetItemRequest.builder().tableName(table).key(key(pk,sk))
                .consistentRead(true).build()).item();
    }
    private static ContractLimit fromLimit(Map<String,AttributeValue> item) {
        return new ContractLimit(txt(item,"contractId"), txt(item,"accountId"),
                number(item,"totalMinorUnits"), number(item,"availableMinorUnits"),
                number(item,"reservedMinorUnits"), number(item,"usedMinorUnits"), number(item,"version"));
    }
    private static Authorization fromAuth(Map<String,AttributeValue> item) {
        return new Authorization(txt(item,"authorizationId"), txt(item,"contractId"),
                OperationType.valueOf(txt(item,"operation")), number(item,"amountMinorUnits"),
                item.containsKey("reversed") && item.get("reversed").bool(),
                item.containsKey("originalAuthorizationId") ? txt(item,"originalAuthorizationId") : null);
    }
    private Put putNew(Map<String,AttributeValue> item) {
        return Put.builder().tableName(table).item(item).conditionExpression("attribute_not_exists(pk)").build();
    }
    private String eventJson(String eventId, String eventType, Command request, String authId,
                             Money amount, ContractLimit next, String occurredAt) {
        Map<String,Object> data = new HashMap<>();
        data.put("event_id", eventId);
        data.put("event_type", eventType);
        data.put("event_version", "1.0");
        data.put("occurred_at", occurredAt);
        data.put("id_autorizacao", authId);
        data.put("id_contrato", request.contractId());
        data.put("tipo_operacao", request.operation());
        data.put("valor", amount.decimal().toPlainString());
        data.put("saldo_reservado", MoneyDecimal.toString(next.reserved()));
        data.put("correlation_id", request.correlationId());
        try {
            return mapper.writeValueAsString(Map.of(
                    "specversion", "1.0", "id", eventId, "type", eventType,
                    "source", "/pod99/authorization-limit-service", "time", occurredAt,
                    "datacontenttype", "application/json", "data", data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha na serialização do evento", e);
        }
    }
    private static Map<String,AttributeValue> key(String pk, String sk) {
        return Map.of("pk", s(pk), "sk", s(sk));
    }
    private static AttributeValue s(String value) {return AttributeValue.builder().s(value).build();}
    private static AttributeValue n(long value) {return AttributeValue.builder().n(Long.toString(value)).build();}
    private static AttributeValue bool(boolean value) {return AttributeValue.builder().bool(value).build();}
    private static long number(Map<String,AttributeValue> item, String attr) {
        if (!item.containsKey(attr) || item.get(attr).n() == null) throw new IllegalStateException("Atributo ausente: "+attr);
        return Long.parseLong(item.get(attr).n());
    }
    private static String txt(Map<String,AttributeValue> item, String attr) {
        if (!item.containsKey(attr) || item.get(attr).s() == null) throw new IllegalStateException("Atributo ausente: "+attr);
        return item.get(attr).s();
    }
    private static final class MoneyDecimal {
        private static String toString(long cents) {
            return java.math.BigDecimal.valueOf(cents,2).toPlainString();
        }
    }
}
