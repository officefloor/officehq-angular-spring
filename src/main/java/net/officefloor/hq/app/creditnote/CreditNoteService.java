package net.officefloor.hq.app.creditnote;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.invoice.Invoice;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CreditNoteService {

    private final CreditNoteRepository creditNotes;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final Clock clock;
    private final Audit audit;

    public CreditNoteService(CreditNoteRepository creditNotes, InvoiceRepository invoices,
            PaymentRepository payments, Clock clock, Audit audit) {
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.payments = payments;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CreditNoteResponse> list(Long projectId, Long invoiceId) {
        find(projectId, invoiceId);
        return creditNotes.findByInvoiceIdOrderByIdAsc(invoiceId).stream().map(CreditNoteResponse::from).toList();
    }

    /**
     * Raises a credit note against an invoice that has been sent to the client and not cancelled. The
     * credit notes on an invoice may not add up to more than the invoice amount. The invoice's status is
     * then worked out again from what has been paid and credited, so a credit that clears what is left settles it.
     */
    @Transactional
    public CreditNoteResponse issue(Long projectId, Long invoiceId, CreditNoteRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Send the invoice before raising a credit note");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been cancelled");
        }
        BigDecimal credited = creditNotes.sumAmountByInvoiceId(invoiceId).add(request.amount());
        if (credited.compareTo(invoice.getAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Credit is more than the invoice amount");
        }
        CreditNote saved = creditNotes.saveAndFlush(new CreditNote(invoiceId, request.amount(), clock.instant()));
        invoice.applySettledTotals(payments.sumAmountByInvoiceId(invoiceId), credited);
        invoices.flush();
        audit.record("CREDIT_NOTE_ISSUED invoice=" + invoiceId + " amount=" + saved.getAmount().toPlainString());
        return CreditNoteResponse.from(saved);
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
