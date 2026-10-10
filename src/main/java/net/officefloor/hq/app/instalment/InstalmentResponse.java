package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * An instalment of an invoice, with the days it is late (past due and still unpaid) and the interest
 * that has accrued on it for those days.
 */
public record InstalmentResponse(Long id, Long invoiceId, BigDecimal amount, LocalDate date, boolean paid,
        long daysLate, BigDecimal interest) {

    static InstalmentResponse from(Instalment instalment, long daysLate, BigDecimal interestPerDay) {
        return new InstalmentResponse(instalment.getId(), instalment.getInvoiceId(), instalment.getAmount(),
                instalment.getDueDate(), instalment.isPaid(), daysLate,
                interestPerDay.multiply(BigDecimal.valueOf(daysLate)).setScale(2, RoundingMode.HALF_UP));
    }
}
