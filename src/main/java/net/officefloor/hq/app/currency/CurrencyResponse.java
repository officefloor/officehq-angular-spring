package net.officefloor.hq.app.currency;

import java.math.BigDecimal;

public record CurrencyResponse(String code, String symbol, BigDecimal roundingStep) {

    public static CurrencyResponse from(Currency currency) {
        return new CurrencyResponse(currency.getCode(), currency.getSymbol(), currency.getRoundingStep());
    }
}
