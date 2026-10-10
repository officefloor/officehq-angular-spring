package net.officefloor.hq.app.taxadjustment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TaxAdjustmentResponse(Long id, LocalDate date, BigDecimal amount, String currency, String reason) {

    static TaxAdjustmentResponse from(TaxAdjustment adjustment) {
        return new TaxAdjustmentResponse(adjustment.getId(), adjustment.getAdjustmentDate(), adjustment.getAmount(),
                adjustment.getCurrency(), adjustment.getReason());
    }
}
