package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A client's money on one screen, in the client's currency, as at today: what has been billed to them on their sent
 * invoices (drafts and void ones are left out), how much of that has been paid, what is still outstanding, and the part
 * of the outstanding that is on invoices past their due date.
 */
public record ClientFinancialSummaryResponse(Long clientId, String currency, LocalDate asOf, BigDecimal billed,
        BigDecimal paid, BigDecimal outstanding, BigDecimal overdue) {
}
