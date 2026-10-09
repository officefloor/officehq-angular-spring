package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.officefloor.hq.app.client.Currency;

/**
 * A client's statement: all of their invoices, each with the project it is for and how much is left
 * to pay, the same invoices grouped by job with what is owed on each job, and the total still owed
 * (what is left to pay across their sent invoices; drafts and void ones are not owed). For the printable
 * summary it also gives the total invoiced on those owed invoices and how much of that has been paid,
 * so the total still owed is what was invoiced less what was paid. All of it is in the client's currency.
 */
public record ClientStatementResponse(Long clientId, String clientName, Currency currency, List<Line> invoices, List<Job> jobs,
        BigDecimal invoiced, BigDecimal paid, BigDecimal outstanding) {

    static ClientStatementResponse from(Long clientId, String clientName, Currency currency, List<Line> invoices) {
        Map<Long, List<Line>> byProject = new LinkedHashMap<>();
        invoices.forEach(l -> byProject.computeIfAbsent(l.projectId(), id -> new ArrayList<>()).add(l));
        List<Job> jobs = byProject.values().stream()
                .map(lines -> new Job(lines.get(0).projectId(), lines.get(0).projectName(), lines, owed(lines)))
                .toList();
        BigDecimal outstanding = owed(invoices);
        BigDecimal invoiced = sum(invoices, Line::amount);
        return new ClientStatementResponse(clientId, clientName, currency, invoices, jobs, invoiced,
                invoiced.subtract(outstanding), outstanding);
    }

    /** What is left to pay across the given invoices, leaving out drafts and void ones. */
    private static BigDecimal owed(List<Line> lines) {
        return sum(lines, Line::amountDue);
    }

    /** Adds up the given figure across the invoices that are owed, leaving out drafts and void ones. */
    private static BigDecimal sum(List<Line> lines, Function<Line, BigDecimal> figure) {
        return lines.stream()
                .filter(l -> l.status() != InvoiceStatus.DRAFT && l.status() != InvoiceStatus.VOID)
                .map(figure)
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
