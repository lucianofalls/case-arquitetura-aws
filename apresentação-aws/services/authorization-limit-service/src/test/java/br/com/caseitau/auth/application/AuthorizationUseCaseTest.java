package br.com.caseitau.auth.application;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import br.com.caseitau.auth.application.AuthorizationUseCase.Command;
import br.com.caseitau.auth.application.AuthorizationUseCase.Receipt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizationUseCaseTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AuthorizationUseCase useCase = new AuthorizationUseCase((cmd, amount, digest) -> {
        calls.incrementAndGet();
        assertEquals(500000L, amount);
        assertEquals(64, digest.length());
        return new Receipt("auth-1", 500000L, 200000L, false);
    });

    @Test void reserveBRLIsConvertedToIntegerMinorUnits() {
        var response = useCase.execute(command("RESERVA", "5000.00"));
        assertEquals("auth-1", response.authorizationId());
        assertEquals(1, calls.get());
    }

    @Test void debitIsNotSilentlyTreatedAsReservation() {
        var error = assertThrows(ApiFailure.class, () -> useCase.execute(command("DEBITO", "5000.00")));
        assertEquals(501, error.status().value());
        assertEquals(0, calls.get());
    }

    @Test void moreThanTwoDecimalPlacesIsInvalid() {
        var error = assertThrows(ApiFailure.class, () -> useCase.execute(command("RESERVA", "10.001")));
        assertEquals(422, error.status().value());
    }

    private static Command command(String type, String value) {
        return new Command("contrato-1", "conta-1", new BigDecimal(value), "BRL",
                type, UUID.randomUUID(), "correlation-1");
    }
}
