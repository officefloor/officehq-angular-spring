package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.deposit.DepositApplication;
import net.officefloor.hq.app.deposit.DepositApplicationRepository;
import net.officefloor.hq.app.deposit.DepositApplicationRequest;
import net.officefloor.hq.app.deposit.DepositApplicationResponse;
import net.officefloor.hq.app.deposit.DepositRepository;
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
    private final CreditNoteRepository creditNotes;
    private final InvoiceRepository invoices;
    private final ClientPaymentRepository clientPayments;
    private final DepositRepository deposits;
    private final DepositApplicationRepository depositApplications;
    private final ClientRepository clients;
    private final Clock clock;
    private final Audit audit;

    public PaymentService(PaymentRepository payments, CreditNoteRepository creditNotes, InvoiceRepository invoices,
            ClientPaymentRepository clientPayments, DepositRepository deposits,
            DepositApplicationRepository depositApplications, ClientRepository clients, Clock clock, Audit audit) {
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.clientPayments = clientPayments;
        this.deposits = deposits;
        this.depositApplications = depositApplications;
        this.clients = clients;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long projectId, Long invoiceId) {
        find(projectId, invoiceId);
        return payments.findByInvoiceIdOrderByDateAscIdAsc(invoiceId).stream().map(PaymentResponse::from).toList();
    }

    /**
     * Records a payment against an invoice that has been sent to the client and is still owing, and
     * works out the invoice's status from what has now been paid and credited. A payment may not take
     * the total paid and credited beyond the invoice amount.
     */
    @Transactional
    public PaymentResponse record(Long projectId, Long invoiceId, PaymentRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        return apply(invoice, request.amount(), () -> new Payment(invoice.getId(), request.amount(), request.date()));
    }

    /**
     * Records one lump payment from a client split across several of their owing invoices. The
     * shares must add up to the whole lump, each invoice may appear once, and each share is recorded
     * as a payment against its own invoice under the same rules as a payment made on its own.
     */
    @Transactional
    public ClientPaymentResponse recordForClient(Long clientId, ClientPaymentRequest request) {
        requireClient(clientId);
        BigDecimal allocated = total(request.allocations(), ClientPaymentRequest.Allocation::amount);
        if (allocated.compareTo(request.amount()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The shares must add up to the whole payment");
        }
        requireDistinct(request.allocations(), ClientPaymentRequest.Allocation::invoiceId);
        ClientPayment lump = clientPayments.saveAndFlush(new ClientPayment(clientId, request.amount(), request.date()));
        List<PaymentResponse> shares = request.allocations().stream()
                .map(a -> apply(findForClient(clientId, a.invoiceId()), a.amount(),
                        () -> new Payment(a.invoiceId(), a.amount(), request.date(), lump.getId())))
                .toList();
        return ClientPaymentResponse.from(lump, shares);
    }

    /**
     * Puts part of a client's held deposits toward several of their owing invoices, today. The
     * shares may not add up to more than is still held, each invoice may appear once, and each share
     * is recorded as a payment against its own invoice under the same rules as a lump payment's.
     */
    @Transactional
    public DepositApplicationResponse applyDeposits(Long clientId, DepositApplicationRequest request) {
        requireClient(clientId);
        BigDecimal allocated = total(request.allocations(), DepositApplicationRequest.Allocation::amount);
        BigDecimal held = deposits.sumAmountByClientId(clientId)
                .subtract(depositApplications.sumAmountByClientId(clientId));
        if (allocated.compareTo(held) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The shares add up to more than the deposits held");
        }
        requireDistinct(request.allocations(), DepositApplicationRequest.Allocation::invoiceId);
        LocalDate today = LocalDate.now(clock);
        DepositApplication application = depositApplications.saveAndFlush(
                new DepositApplication(clientId, allocated, today));
        List<PaymentResponse> shares = request.allocations().stream()
                .map(a -> apply(findForClient(clientId, a.invoiceId()), a.amount(),
                        () -> Payment.fromDeposits(a.invoiceId(), a.amount(), today, application.getId())))
                .toList();
        audit.record("DEPOSIT_APPLIED id=" + application.getId() + " client=" + clientId
                + " amount=" + application.getAmount().toPlainString());
        return DepositApplicationResponse.from(application, shares);
    }

    private void requireClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
    }

    private static <A> BigDecimal total(List<A> allocations, Function<A, BigDecimal> amount) {
        return allocations.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <A> void requireDistinct(List<A> allocations, Function<A, Long> invoiceId) {
        Set<Long> seen = new HashSet<>();
        for (A a : allocations) {
            if (!seen.add(invoiceId.apply(a))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each invoice may be paid only once");
            }
        }
    }

    private PaymentResponse apply(Invoice invoice, BigDecimal amount, Supplier<Payment> payment) {
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Send the invoice before recording a payment");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been cancelled");
        }
        if (invoice.getStatus() == InvoiceStatus.WRITTEN_OFF) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has been written off");
        }
        if (!invoice.getStatus().isOwing()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice is already paid");
        }
        BigDecimal paid = payments.sumAmountByInvoiceId(invoice.getId()).add(amount);
        BigDecimal credited = creditNotes.sumAmountByInvoiceId(invoice.getId());
        BigDecimal settled = paid.add(credited);
        if (settled.compareTo(invoice.getAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is more than the balance due");
        }
        Payment saved = payments.saveAndFlush(payment.get());
        invoice.applySettledTotals(paid, credited);
        invoices.flush();
        audit.record("PAYMENT_RECORDED id=" + saved.getId() + " amount=" + saved.getAmount().toPlainString());
        return PaymentResponse.from(saved);
    }

    private Invoice findForClient(Long clientId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getClient().getId().equals(clientId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
