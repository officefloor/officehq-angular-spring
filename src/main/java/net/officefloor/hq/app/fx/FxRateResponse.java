package net.officefloor.hq.app.fx;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FxRateResponse(Long id, String currency, LocalDate date, BigDecimal rate) {

    static FxRateResponse from(FxRate rate) {
        return new FxRateResponse(rate.getId(), rate.getCurrency(), rate.getRateDate(), rate.getRate());
    }
}
