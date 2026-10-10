package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.credit.ClientCreditResponse;
import net.officefloor.hq.app.credit.ClientCreditService;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.currency.CurrencyService;
import net.officefloor.hq.app.deposit.DepositApplication;
import net.officefloor.hq.app.deposit.DepositApplicationRepository;
import net.officefloor.hq.app.deposit.DepositApplicationRequest;
import net.officefloor.hq.app.deposit.DepositApplicationResponse;
import net.officefloor.hq.app.fx.FxRateService;
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
    private final DepositApplicationRepository depositApplications;
    private final ClientCreditService credit;
    private final ClientRepository clients;
    private final FxRateService fxRates;
    private final CurrencyService currencies;
    private final Clock clock;
    private final Audit audit;

    public PaymentService(PaymentRepository payments, CreditNoteRepository creditNotes, InvoiceRepository invoices,
            ClientPaymentRepository clientPayments, DepositApplicationRepository depositApplications,
            ClientCreditService credit, ClientRepository clients, FxRateService fxRates, CurrencyService currencies,
            Clock clock, Audit audit) {
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.clientPayments = clientPayments;
        this.depositApplications = depositApplications;
        this.credit = credit;
        this.clients = clients;
        this.fxRates = fxRates;
        this.currencies = currencies;
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
     * the total paid and credited beyond the invoice amount. A payment made in another currency is
     * converted into the invoice's currency at the rates in effect on the payment's date, and settles
     * that much of the invoice.
     */
    @Transactional
    public PaymentResponse record(Long projectId, Long invoiceId, PaymentRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        String currency = request.currency() == null ? invoice.getCurrency()
                : request.currency().trim().toUpperCase(Locale.ROOT);
        if (currency.equals(invoice.getCurrency())) {
            return apply(invoice, request.amount(),
                    () -> new Payment(invoice.getId(), request.amount(), request.date()));
        }
        if (!currencies.exists(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown currency");
        }
        BigDecimal settles = fxRates.convert(currency, invoice.getCurrency(), request.date(), request.amount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No exchange rate for " + currency + " to " + invoice.getCurrency() + " on " + request.date()));
        if (settles.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The payment is too small to pay anything");
        }
        return apply(invoice, settles, () -> Payment.inForeignCurrency(invoice.getId(), settles, request.date(),
                currency, request.amount()));
    }

    /** A client's lump payments, newest first. */
    @Transactional(readOnly = true)
    public List<ClientPaymentSummaryResponse> listForClient(Long clientId) {
        requireClient(clientId);
        return clientPayments.findByClientIdOrderByDateDescIdDesc(clientId).stream()
                .map(ClientPaymentSummaryResponse::from).toList();
    }

    /**
     * The remittance note for one of a client's lump payments: each invoice it was split across, with the share of the
     * lump put toward it (in the client's currency) and what that settled on the invoice (in the invoice's currency).
     */
    @Transactional(readOnly = true)
    public RemittanceResponse remittance(Long clientId, Long clientPaymentId) {
        String currency = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"))
                .getCurrency();
        ClientPayment lump = clientPayments.findById(clientPaymentId)
                .filter(p -> p.getClientId().equals(clientId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown payment"));
        List<RemittanceResponse.Line> lines = payments.findByClientPaymentIdOrderByIdAsc(lump.getId()).stream()
                .map(share -> {
                    Invoice invoice = invoices.findById(share.getInvoiceId()).orElseThrow();
                    return new RemittanceResponse.Line(invoice.getId(), invoice.getProject().getId(),
                            invoice.getProject().getName(),
                            share.getShareAmount() != null ? share.getShareAmount() : share.getAmount(),
                            share.getAmount(), invoice.getCurrency());
                })
                .toList();
        return new RemittanceResponse(lump.getId(), clientId, currency, lump.getAmount(), lump.getDate(),
                lump.getFromDeposits(), lump.getFromCreditNotes(), lump.getToCredit(), lines);
    }

    /**
     * Records one lump payment from a client split across several of their owing invoices. With
     * {@code useCredit} the client's credit is used up first (held deposits, then unused credit
     * notes) and the money received covers the rest; otherwise the money received covers it all.
     * The shares may not add up to more than the money received plus any credit used, and whatever
     * of the money received is left over is kept as credit for the client, so the money received
     * plus the credit used always equals the shares plus what is kept. Each invoice may appear
     * once, and each share is recorded as a payment against its own invoice under the same rules
     * as a payment made on its own. The lump and its shares are in the client's currency; a share
     * against an invoice in another currency is converted into the invoice's currency at the rates
     * in effect on the payment's date, so it settles the right amount of that invoice.
     */
    @Transactional
    public ClientPaymentResponse recordForClient(Long clientId, ClientPaymentRequest request) {
        String currency = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"))
                .getCurrency();
        BigDecimal allocated = total(request.allocations(), ClientPaymentRequest.Allocation::amount).setScale(2);
        BigDecimal received = request.amount().setScale(2);
        BigDecimal fromDeposits = BigDecimal.ZERO.setScale(2);
        BigDecimal fromCreditNotes = BigDecimal.ZERO.setScale(2);
        if (request.usesCredit()) {
            ClientCreditResponse available = credit.available(clientId);
            fromDeposits = allocated.min(available.deposits());
            fromCreditNotes = allocated.subtract(fromDeposits).min(available.creditNotes());
        }
        BigDecimal fromReceived = allocated.subtract(fromDeposits).subtract(fromCreditNotes);
        if (fromReceived.compareTo(received) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, request.usesCredit()
                    ? "The shares add up to more than the payment and the credit available"
                    : "The shares add up to more than the payment");
        }
        BigDecimal toCredit = received.subtract(fromReceived);
        requireDistinct(request.allocations(), ClientPaymentRequest.Allocation::invoiceId);
        ClientPayment lump = clientPayments.saveAndFlush(new ClientPayment(clientId, received, request.date(),
                fromDeposits, fromCreditNotes, toCredit));
        List<PaymentResponse> shares = request.allocations().stream()
                .map(a -> {
                    Invoice invoice = findForClient(clientId, a.invoiceId());
                    BigDecimal settles = settles(currency, invoice, request.date(), a.amount());
                    return apply(invoice, settles,
                            () -> new Payment(a.invoiceId(), settles, request.date(), lump.getId(), a.amount()));
                })
                .toList();
        audit.record("CLIENT_PAYMENT_RECORDED id=" + lump.getId() + " client=" + clientId
                + " amount=" + lump.getAmount().toPlainString()
                + " fromDeposits=" + lump.getFromDeposits().toPlainString()
                + " fromCreditNotes=" + lump.getFromCreditNotes().toPlainString()
                + " toCredit=" + lump.getToCredit().toPlainString());
        return ClientPaymentResponse.from(lump, shares);
    }

    /**
     * Puts part of a client's held deposits toward several of their owing invoices, today. The
     * shares may not add up to more than is still held, each invoice may appear once, and each share
     * is recorded as a payment against its own invoice under the same rules as a lump payment's,
     * converted into the invoice's currency at today's rates when it differs from the client's.
     */
    @Transactional
    public DepositApplicationResponse applyDeposits(Long clientId, DepositApplicationRequest request) {
        requireClient(clientId);
        BigDecimal allocated = total(request.allocations(), DepositApplicationRequest.Allocation::amount);
        BigDecimal held = credit.heldDeposits(clientId);
        if (allocated.compareTo(held) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The shares add up to more than the deposits held");
        }
        requireDistinct(request.allocations(), DepositApplicationRequest.Allocation::invoiceId);
        LocalDate today = LocalDate.now(clock);
        DepositApplication application = depositApplications.saveAndFlush(
                new DepositApplication(clientId, allocated, today));
        String currency = clients.findById(clientId).orElseThrow().getCurrency();
        List<PaymentResponse> shares = request.allocations().stream()
                .map(a -> {
                    Invoice invoice = findForClient(clientId, a.invoiceId());
                    BigDecimal settles = settles(currency, invoice, today, a.amount());
                    return apply(invoice, settles,
                            () -> Payment.fromDeposits(a.invoiceId(), settles, today, application.getId()));
                })
                .toList();
        audit.record("DEPOSIT_APPLIED id=" + application.getId() + " client=" + clientId
                + " amount=" + application.getAmount().toPlainString());
        return DepositApplicationResponse.from(application, shares);
    }

    /**
     * What a share in the client's currency settles on the invoice: the share converted into the invoice's currency
     * at the rates in effect on the date. Refused when there is no rate by then or it converts to nothing.
     */
    private BigDecimal settles(String currency, Invoice invoice, LocalDate date, BigDecimal share) {
        BigDecimal settles = fxRates.convert(currency, invoice.getCurrency(), date, share)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No exchange rate for " + invoice.getCurrency() + " on " + date));
        if (settles.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The share for invoice #" + invoice.getId() + " is too small to pay anything");
        }
        return settles;
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
        if (invoice.getDueDate() != null && saved.getDate().isBefore(invoice.getDueDate())) {
            invoice.takeRebateIfEarned(payments.sumAmountByInvoiceIdPaidBefore(invoice.getId(), invoice.getDueDate()),
                    credited);
        }
        invoice.applySettledTotals(paid, credited);
        invoices.flush();
        audit.record("PAYMENT_RECORDED id=" + saved.getId() + " amount=" + saved.getAmount().toPlainString()
                + (saved.getPaidCurrency() == null ? ""
                        : " paid=" + saved.getPaidAmount().toPlainString() + " " + saved.getPaidCurrency()));
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
