package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.invoice.Invoice;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InstalmentService {

    private final InstalmentRepository instalments;
    private final InvoiceRepository invoices;
    private final Audit audit;

    public InstalmentService(InstalmentRepository instalments, InvoiceRepository invoices, Audit audit) {
        this.instalments = instalments;
        this.invoices = invoices;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<InstalmentResponse> list(Long projectId, Long invoiceId) {
        find(projectId, invoiceId);
        return instalments.findByInvoiceIdOrderByDueDateAscIdAsc(invoiceId).stream()
                .map(InstalmentResponse::from).toList();
    }

    /**
     * Schedules an instalment of an invoice that is still to be paid. The instalments on an invoice may
     * not add up to more than the invoice amount.
     */
    @Transactional
    public InstalmentResponse schedule(Long projectId, Long invoiceId, InstalmentRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        requireOpen(invoice);
        BigDecimal scheduled = instalments.sumAmountByInvoiceId(invoiceId).add(request.amount());
        if (scheduled.compareTo(invoice.getAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instalments are more than the invoice amount");
        }
        Instalment saved = instalments.saveAndFlush(new Instalment(invoiceId, request.amount(), request.date()));
        audit.record("INSTALMENT_SCHEDULED invoice=" + invoiceId + " amount=" + saved.getAmount().toPlainString()
                + " date=" + saved.getDueDate());
        return InstalmentResponse.from(saved);
    }

    /** Takes an instalment off an invoice's schedule. */
    @Transactional
    public void remove(Long projectId, Long invoiceId, Long instalmentId) {
        Invoice invoice = find(projectId, invoiceId);
        requireOpen(invoice);
        Instalment instalment = instalments.findById(instalmentId)
                .filter(i -> i.getInvoiceId().equals(invoiceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instalment"));
        instalments.delete(instalment);
        audit.record("INSTALMENT_REMOVED invoice=" + invoiceId + " id=" + instalmentId);
    }

    private static void requireOpen(Invoice invoice) {
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been cancelled");
        }
        if (invoice.getStatus() == InvoiceStatus.WRITTEN_OFF) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been written off");
        }
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been paid");
        }
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
