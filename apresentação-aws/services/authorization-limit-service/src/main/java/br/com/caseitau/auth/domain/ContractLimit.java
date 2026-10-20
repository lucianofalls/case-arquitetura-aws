package br.com.caseitau.auth.domain;

/**
 * Aggregate Root - contract's available, reserved and used limit.
 *
 * A single DynamoDB item is the consistency boundary for an individual
 * contract. All state transitions are pure and preserve:
 *    available >= 0, reserved >= 0, used >= 0
 *    available + reserved + used == totalLimit
 *
 * Monetary values use integer minor units (BRL cents).
 */
public record ContractLimit(String contractId, String accountId, long total, long available,
                            long reserved, long used, long version) {

    public ContractLimit {
        if (contractId == null || contractId.isBlank() || accountId == null || accountId.isBlank()
                || total < 0 || available < 0 || reserved < 0 || used < 0 || version < 0) {
            throw new IllegalArgumentException("Estado de limite inválido");
        }
        try {
            if (Math.addExact(Math.addExact(available, reserved), used) != total) {
                throw new IllegalArgumentException("A soma das posições difere do limite total");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Overflow no limite");
        }
    }

    public ContractLimit reserve(long cents) {
        requirePositive(cents);
        if (available < cents) throw new InsufficientLimit();
        return change(-cents, cents, 0);
    }

    public ContractLimit debit(long cents) {
        requirePositive(cents);
        if (available < cents) throw new InsufficientLimit();
        return change(-cents, 0, cents);
    }

    /**
     * Credit applies a principal repayment/limit restoration, NOT a limit
     * increase. The product domain must approve this semantic assumption.
     */
    public ContractLimit credit(long cents) {
        requirePositive(cents);
        if (used < cents) throw new InvalidTransition("Crédito excede limite utilizado");
        return change(cents, 0, -cents);
    }

    public ContractLimit reverse(OperationType originalType, long cents) {
        requirePositive(cents);
        return switch (originalType) {
            case RESERVA -> {
                if (reserved < cents) throw new InvalidTransition("Reserva insuficiente para estorno");
                yield change(cents, -cents, 0);
            }
            case DEBITO -> {
                if (used < cents) throw new InvalidTransition("Valor usado insuficiente para estorno");
                yield change(cents, 0, -cents);
            }
            default -> throw new InvalidTransition("Somente RESERVA ou DEBITO podem ser estornados neste MVP");
        };
    }

    private ContractLimit change(long dAvailable, long dReserved, long dUsed) {
        try {
            return new ContractLimit(contractId, accountId, total,
                    Math.addExact(available, dAvailable),
                    Math.addExact(reserved, dReserved),
                    Math.addExact(used, dUsed),
                    Math.addExact(version, 1));
        } catch (ArithmeticException overflow) {
            throw new InvalidTransition("Overflow durante a transição financeira");
        }
    }

    public static void requirePositive(long cents) {
        if (cents <= 0) throw new InvalidTransition("Valor deve ser positivo");
    }

    public static class InsufficientLimit extends RuntimeException {
        public InsufficientLimit() { super("Limite insuficiente"); }
    }

    public static class InvalidTransition extends RuntimeException {
        public InvalidTransition(String detail) { super(detail); }
    }
}
