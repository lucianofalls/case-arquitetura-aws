package br.com.caseitau.auth.application;

import br.com.caseitau.auth.domain.Money;
import br.com.caseitau.auth.domain.OperationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Application orchestration: only business invariants and ports.
 * No DynamoDB and no messaging dependencies.
 */
@Service
public final class AuthorizationUseCase {
    private final AuthorizationStore store;
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public AuthorizationUseCase(AuthorizationStore store) { this.store = store; }

    public Receipt execute(Command command) {
        if (command.idempotencyKey() == null) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key obrigatório");
        }
        if (command.contractId() == null || command.contractId().isBlank() ||
            command.accountId() == null || command.accountId().isBlank()) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Conta e contrato são obrigatórios");
        }
        final OperationType type;
        try {
            type = OperationType.valueOf(command.operation());
        } catch (RuntimeException ex) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OPERATION", "Operação inválida");
        }
        if (type == OperationType.ESTORNO && (command.originalAuthorizationId() == null
                || command.originalAuthorizationId().isBlank())) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ORIGINAL_REQUIRED", "Estorno exige id_autorizacao_original");
        }
        if (type != OperationType.ESTORNO && command.originalAuthorizationId() != null) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ORIGINAL", "Referência original válida apenas para ESTORNO");
        }
        final Money amount;
        try {
            amount = Money.of(command.amount(), command.currency());
        } catch (RuntimeException ex) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", ex.getMessage());
        }
        try {
            Map<String,Object> canonical = Map.of(
                    "contractId", command.contractId(),
                    "accountId", command.accountId(),
                    "amount", amount.cents(),
                    "currency", amount.currency(),
                    "operation", type.name(),
                    "originalId", command.originalAuthorizationId() == null ? "" : command.originalAuthorizationId(),
                    "merchantId", command.merchantId() == null ? "" : command.merchantId(),
                    "metadata", command.metadata() == null ? Map.of() : command.metadata());
            String requestHash = sha256(CANONICAL.writeValueAsString(canonical));
            return store.apply(command, type, amount, requestHash);
        } catch (ApiFailure e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao canonicalizar solicitação", e);
        }
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }

    public record Command(String contractId, String accountId, BigDecimal amount, String currency,
                          String operation, UUID idempotencyKey, String correlationId,
                          String originalAuthorizationId, String merchantId, Map<String, Object> metadata) {
        public Command(String contractId, String accountId, BigDecimal amount, String currency,
                       String operation, UUID idempotencyKey, String correlationId) {
            this(contractId, accountId, amount, currency, operation, idempotencyKey, correlationId,
                    null, null, Map.of());
        }
    }

    public record Receipt(String authorizationId, long reservedAfterMinorUnits,
                          long availableAfterMinorUnits, boolean replayed, OperationType operationType) {}

    /** Outbound port. Infrastructure is responsible for concurrency and durable replay. */
    public interface AuthorizationStore {
        Receipt apply(Command command, OperationType type, Money money, String payloadHash);
    }

    public static final class ApiFailure extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        public ApiFailure(HttpStatus status, String code, String detail) {
            super(detail); this.status = status; this.code = code;
        }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
