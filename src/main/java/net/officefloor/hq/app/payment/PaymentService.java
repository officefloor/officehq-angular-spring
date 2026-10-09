package net.officefloor.hq.app.payment;

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
public class PaymentService {

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final Audit audit;

    public PaymentService(PaymentRepository payments, InvoiceRepository invoices, Audit audit) {
        this.payments = payments;
        this.invoices = invoices;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long projectId, Long invoiceId) {
        find(projectId, invoiceId);
        return payments.findByInvoiceIdOrderByDateAscIdAsc(invoiceId).stream().map(PaymentResponse::from).toList();
    }

    /**
     * Records a payment against an invoice that has been sent to the client. A payment may not take
     * the total paid beyond the invoice amount.
     */
    @Transactional
    public PaymentResponse record(Long projectId, Long invoiceId, PaymentRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Send the invoice before recording a payment");
        }
        if (payments.sumAmountByInvoiceId(invoiceId).add(request.amount()).compareTo(invoice.getAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is more than the balance due");
        }
        Payment saved = payments.saveAndFlush(new Payment(invoiceId, request.amount(), request.date()));
        audit.record("PAYMENT_RECORDED id=" + saved.getId() + " invoice=" + invoiceId
                + " amount=" + saved.getAmount().toPlainString() + " date=" + saved.getDate());
        return PaymentResponse.from(saved);
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
