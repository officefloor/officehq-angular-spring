package net.officefloor.hq.app.adjustmentnote;

import java.math.BigDecimal;
import java.time.Clock;
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
public class AdjustmentNoteService {

    private final AdjustmentNoteRepository adjustmentNotes;
    private final InvoiceRepository invoices;
    private final Clock clock;
    private final Audit audit;

    public AdjustmentNoteService(AdjustmentNoteRepository adjustmentNotes, InvoiceRepository invoices, Clock clock,
            Audit audit) {
        this.adjustmentNotes = adjustmentNotes;
        this.invoices = invoices;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdjustmentNoteResponse> list(Long projectId, Long invoiceId) {
        find(projectId, invoiceId);
        return adjustmentNotes.findByInvoiceIdOrderByIdAsc(invoiceId).stream().map(AdjustmentNoteResponse::from)
                .toList();
    }

    /**
     * Issues an adjustment note against an invoice that has been sent and not cancelled or written off. The
     * invoice itself is left exactly as sent; the notes together may not take its adjusted total below zero.
     */
    @Transactional
    public AdjustmentNoteResponse issue(Long projectId, Long invoiceId, AdjustmentNoteRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Edit a draft invoice directly instead");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been cancelled");
        }
        if (invoice.getStatus() == InvoiceStatus.WRITTEN_OFF) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been written off");
        }
        if (request.amount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An adjustment must change the total");
        }
        BigDecimal adjusted = invoice.getAmount().add(adjustmentNotes.sumAmountByInvoiceId(invoiceId))
                .add(request.amount());
        if (adjusted.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The adjusted total cannot be below zero");
        }
        AdjustmentNote saved = adjustmentNotes.saveAndFlush(
                new AdjustmentNote(invoiceId, request.amount(), request.reason().strip(), clock.instant()));
        audit.record("ADJUSTMENT_NOTE_ISSUED invoice=" + invoiceId + " amount=" + saved.getAmount().toPlainString());
        return AdjustmentNoteResponse.from(saved);
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
