package br.com.caseitau.auth.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractLimitTest {
    private ContractLimit initial() {
        return new ContractLimit("contrato-1", "conta-1", 700_000, 700_000, 0, 0, 0);
    }

    @Test void twoConcurrentReservationsCannotBeAcceptedFromTheSameAggregateSnapshot() {
        var afterFirst = initial().reserve(500_000);
        assertEquals(200_000, afterFirst.available());
        assertEquals(500_000, afterFirst.reserved());
        assertEquals(1, afterFirst.version());
        assertThrows(ContractLimit.InsufficientLimit.class, () -> afterFirst.reserve(500_000));
    }

    @Test void debitCreatesUsedPositionAndCreditRestoresIt() {
        var debited = initial().debit(250_000);
        assertEquals(450_000, debited.available());
        assertEquals(250_000, debited.used());
        var credited = debited.credit(250_000);
        assertEquals(initial().available(), credited.available());
        assertEquals(0, credited.used());
        assertThrows(ContractLimit.InvalidTransition.class, () -> credited.credit(1));
    }

    @Test void reverseReservationAndDebitPreserveTotal() {
        var reserved = initial().reserve(500_000);
        var reverted = reserved.reverse(OperationType.RESERVA, 500_000);
        assertEquals(700_000, reverted.available());
        assertEquals(0, reverted.reserved());

        var debited = initial().debit(200_000);
        var reversed = debited.reverse(OperationType.DEBITO, 200_000);
        assertEquals(700_000, reversed.available());
        assertEquals(0, reversed.used());
    }

    @Test void inconsistentAccountingPositionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContractLimit("a", "b", 100, 110, 0, 0, 0));
    }
}
