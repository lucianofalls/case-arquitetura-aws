package br.com.caseitau.auth.domain;

import java.util.Objects;

/** Entity persisted as AUTH item. */
public record Authorization(String id, String contractId, OperationType type, long amountMinorUnits,
                            boolean reversed, String originalAuthorizationId) {
    public Authorization {
        Objects.requireNonNull(id);
        Objects.requireNonNull(contractId);
        Objects.requireNonNull(type);
        ContractLimit.requirePositive(amountMinorUnits);
        if (type == OperationType.ESTORNO && (originalAuthorizationId == null || originalAuthorizationId.isBlank())) {
            throw new IllegalArgumentException("Estorno exige a autorização original");
        }
    }
}
