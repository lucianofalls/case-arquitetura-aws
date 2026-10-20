package br.com.caseitau.auth.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Use case: authorize a LIMIT RESERVATION only.
 * DEBITO, CREDITO and ESTORNO require independent financial state machines.
 */
@Service
public final class AuthorizationUseCase {
    private final AuthorizationStore store;

    public AuthorizationUseCase(AuthorizationStore store) {
        this.store = store;
    }

    public Receipt execute(Command command) {
        if (command.idempotencyKey() == null) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key obrigatório");
        }
        if (!"RESERVA".equals(command.operation())) {
            if ("DEBITO".equals(command.operation()) || "CREDITO".equals(command.operation()) || "ESTORNO".equals(command.operation())) {
                throw new ApiFailure(HttpStatus.NOT_IMPLEMENTED, "OPERATION_NOT_IMPLEMENTED", "Operação prevista no case, mas ainda não implementada");
            }
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OPERATION", "Tipo de operação inválido");
        }
        if (!"BRL".equals(command.currency())) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENCY", "Primeiro slice suporta BRL");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Valor deve ser positivo");
        }

        final long amountMinorUnits;
        try {
            BigDecimal money = command.amount().setScale(2, RoundingMode.UNNECESSARY);
            amountMinorUnits = money.movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Valor deve ser decimal(18,2)");
        }
        // The idempotency digest includes all business arguments, not just the amount.
        String canonical = command.contractId() + "|" + command.accountId() + "|" + amountMinorUnits
                + "|" + command.currency() + "|" + command.operation();
        return store.reserve(command, amountMinorUnits, sha256(canonical));
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
                          String operation, UUID idempotencyKey, String correlationId) {
    }

    public record Receipt(String authorizationId, long reservedAfterMinorUnits, long availableAfterMinorUnits,
                          boolean replayed) {
    }

    public interface AuthorizationStore {
        Receipt reserve(Command command, long amountMinorUnits, String payloadHash);
    }

    public static final class ApiFailure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public ApiFailure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
