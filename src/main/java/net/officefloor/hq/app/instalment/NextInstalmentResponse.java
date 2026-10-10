package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The earliest unpaid instalment of an invoice, and whether it is already past due as of today. */
public record NextInstalmentResponse(Long id, BigDecimal amount, LocalDate date, boolean overdue) {
}
