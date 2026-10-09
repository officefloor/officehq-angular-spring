package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A client's statement: all of their invoices, each with the project it is for and how much is left
 * to pay, and the total still owed (what is left to pay across their sent invoices).
 */
public record ClientStatementResponse(Long clientId, String clientName, List<Line> invoices,
        BigDecimal outstanding) {

    /** One invoice on a statement. */
    public record Line(Long id, Long projectId, String projectName, BigDecimal amount, InvoiceStatus status,
            LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue) {

        static Line from(Invoice invoice, BigDecimal paid) {
            return new Line(invoice.getId(), invoice.getProject().getId(), invoice.getProject().getName(),
                    invoice.getAmount(), invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                    invoice.getAmount().subtract(paid));
        }
    }
}
