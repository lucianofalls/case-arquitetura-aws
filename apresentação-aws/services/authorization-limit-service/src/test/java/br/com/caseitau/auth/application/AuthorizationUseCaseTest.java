package br.com.caseitau.auth.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import br.com.caseitau.auth.application.AuthorizationUseCase.Command;
import br.com.caseitau.auth.application.AuthorizationUseCase.Receipt;
import br.com.caseitau.auth.domain.OperationType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationUseCaseTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AuthorizationUseCase useCase = new AuthorizationUseCase((cmd, operation, money, digest) -> {
        calls.incrementAndGet();
        assertEquals(500_000L, money.cents());
        assertEquals(64, digest.length());
        return new Receipt("auth-1", 500_000L, 200_000L, false, operation);
    });

    @Test void brlIsConvertedToCentavos() {
        assertEquals("auth-1", useCase.execute(command("RESERVA", "5000.00")).authorizationId());
    }

    @Test void debitAndCreditHaveTheirOwnIntent() {
        assertEquals(OperationType.DEBITO, useCase.execute(command("DEBITO", "5000.00")).operationType());
        assertEquals(OperationType.CREDITO, useCase.execute(command("CREDITO", "5000.00")).operationType());
    }

    @Test void estornoRequiresOriginalAuthorizationId() {
        var error = assertThrows(ApiFailure.class, () -> useCase.execute(command("ESTORNO", "5000.00")));
        assertEquals(422, error.status().value());
    }

    @Test void moreThanTwoDecimalPlacesAreRejected() {
        var error = assertThrows(ApiFailure.class, () -> useCase.execute(command("RESERVA", "10.001")));
        assertEquals(422, error.status().value());
    }

    private Command command(String type, String value) {
        return new Command("contrato-1", "conta-1", new BigDecimal(value), "BRL",
                type, UUID.randomUUID(), "correlation-1", null, null, Map.of());
    }
}
