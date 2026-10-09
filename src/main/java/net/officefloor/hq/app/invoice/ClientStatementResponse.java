package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A client's statement: all of their invoices, each with the project it is for and how much is left
 * to pay, the same invoices grouped by job with what is owed on each job, and the total still owed
 * (what is left to pay across their sent invoices; drafts and void ones are not owed).
 */
public record ClientStatementResponse(Long clientId, String clientName, List<Line> invoices, List<Job> jobs,
        BigDecimal outstanding) {

    static ClientStatementResponse from(Long clientId, String clientName, List<Line> invoices) {
        Map<Long, List<Line>> byProject = new LinkedHashMap<>();
        invoices.forEach(l -> byProject.computeIfAbsent(l.projectId(), id -> new ArrayList<>()).add(l));
        List<Job> jobs = byProject.values().stream()
                .map(lines -> new Job(lines.get(0).projectId(), lines.get(0).projectName(), lines, owed(lines)))
                .toList();
        return new ClientStatementResponse(clientId, clientName, invoices, jobs, owed(invoices));
    }

    /** What is left to pay across the given invoices, leaving out drafts and void ones. */
    private static BigDecimal owed(List<Line> lines) {
        return lines.stream()
                .filter(l -> l.status() != InvoiceStatus.DRAFT && l.status() != InvoiceStatus.VOID)
                .map(Line::amountDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** One job on a statement: its invoices and the subtotal still owed on them. */
    public record Job(Long projectId, String projectName, List<Line> invoices, BigDecimal subtotal) {
    }

    /** One invoice on a statement. */
    public record Line(Long id, Long projectId, String projectName, BigDecimal amount, InvoiceStatus status,
            LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue) {

        static Line from(Invoice invoice, BigDecimal paid) {
            return new Line(invoice.getId(), invoice.getProject().getId(), invoice.getProject().getName(),
                    invoice.getAmount(), invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                    invoice.amountDue(paid));
        }
    }
}
