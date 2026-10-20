package br.com.caseitau.accounting;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * INBOX-only first vertical slice. It persists receipt of accounting events;
 * it does NOT perform actual bank accounting or post to the general ledger.
 *
 * TODO: when adding irreversible downstream side effects, use an explicit
 * accounting state machine / local outbox instead of treating inbox as done.
 */
@Component
@ConditionalOnProperty(name = "app.sqs.queue-url", matchIfMissing = false)
public class AccountingEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(AccountingEventConsumer.class);
    private final SqsClient sqs;
    private final DynamoDbClient dynamo;
    private final ObjectMapper json;
    private final String queueUrl;
    private final String table;

    public AccountingEventConsumer(SqsClient sqs, DynamoDbClient dynamo, ObjectMapper json,
                                   @Value("${app.sqs.queue-url}") String queueUrl,
                                   @Value("${app.ddb.table}") String table) {
        this.sqs = sqs;
        this.dynamo = dynamo;
        this.json = json;
        this.queueUrl = queueUrl;
        this.table = table;
    }

    @Scheduled(fixedDelayString = "${app.sqs.poll-delay-ms:2000}")
    public void poll() {
        if (queueUrl == null || queueUrl.isBlank()) return;
        var result = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(10).build());
        for (var message : result.messages()) {
            try {
                JsonNode envelope = json.readTree(message.body());
                // EventBridge custom event envelope wraps the CloudEvent in detail.
                JsonNode detail = envelope.path("detail");
                if (detail.isTextual()) detail = json.readTree(detail.asText());
                JsonNode event = detail.has("id") ? detail : envelope;
                String eventId = event.path("id").asText();
                String eventType = event.path("type").asText();
                if (eventId.isBlank() || !java.util.Set.of("TransacaoAutorizada", "LimiteAlterado", "TransacaoEstornada").contains(eventType)) {
                    // Malformed/unknown event: leave on queue for DLQ after maxReceiveCount.
                    log.warn("Evento incompatível na fila de contabilidade: messageId={}", message.messageId());
                    continue;
                }

                try {
                    dynamo.putItem(PutItemRequest.builder().tableName(table)
                            .item(Map.of(
                                    "pk", s("EVENT#" + eventId),
                                    "sk", s("INBOX"),
                                    "entityType", s("CONSUMED_EVENT"),
                                    "eventType", s(eventType),
                                    "receivedAt", s(Instant.now().toString()),
                                    "status", s("RECEIVED_FOR_ACCOUNTING")))
                            .conditionExpression("attribute_not_exists(pk)").build());
                    log.info("Evento contábil recebido, ainda não contabilizado: eventId={}", eventId);
                } catch (ConditionalCheckFailedException duplicate) {
                    log.info("Evento contábil duplicado ignorado: eventId={}", eventId);
                }
                // Only acknowledge after durable inbox write or recognized duplicate.
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
            } catch (Exception e) {
                log.error("Falha ao receber evento contábil, mensagem seguirá retry/DLQ: messageId={}",
                        message.messageId(), e);
                // No delete: message returns after visibility timeout.
            }
        }
    }

    private static AttributeValue s(String text) {
        return AttributeValue.builder().s(text).build();
    }
}
