package br.com.caseitau.auth.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Value Object, no binary floating point in domain monetary operations. */
public record Money(String currency, long cents) {
    public Money {
        if (!"BRL".equals(currency)) throw new IllegalArgumentException("Somente BRL no primeiro slice");
        ContractLimit.requirePositive(cents);
    }
    public static Money of(BigDecimal value, String currency) {
        Objects.requireNonNull(value);
        if (value.precision() > 18 || value.scale() > 2) {
            throw new IllegalArgumentException("decimal(18,2) esperado");
        }
        try {
            return new Money(currency, value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("decimal(18,2) inválido", e);
        }
    }
    public BigDecimal decimal() { return BigDecimal.valueOf(cents, 2); }
}
