package net.officefloor.hq.app.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a currency's amounts are rounded when shown: to the nearest multiple of its rounding step, never finer than its
 * smallest unit, halves away from zero. It is the rule an invoice's figures are shown with, so totals built from
 * rounded figures agree to the cent with the figures they add up.
 */
public record MoneyRule(BigDecimal step) {

    /** The rule for a currency nothing is known about: whole cents. */
    public static final MoneyRule CENTS = new MoneyRule(new BigDecimal("0.01"));

    static MoneyRule of(Currency currency) {
        BigDecimal smallest = BigDecimal.ONE.movePointLeft(currency.getDecimals());
        return new MoneyRule(currency.getRoundingStep().max(smallest));
    }

    /** The amount rounded to this rule, in cents; null stays null. */
    public BigDecimal round(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal cents = amount.setScale(2, RoundingMode.HALF_UP);
        return cents.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.HALF_UP);
    }
}
