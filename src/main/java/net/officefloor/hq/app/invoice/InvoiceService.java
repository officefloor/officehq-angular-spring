package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.creditnote.CreditNote;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.deposit.Deposit;
import net.officefloor.hq.app.deposit.DepositRepository;
import net.officefloor.hq.app.fx.FxRateService;
import net.officefloor.hq.app.payment.Payment;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import net.officefloor.hq.app.refund.Refund;
import net.officefloor.hq.app.refund.RefundRepository;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ProjectRepository projects;
    private final PaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final ClientRepository clients;
    private final DepositRepository deposits;
    private final RefundRepository refunds;
    private final SettingsService settings;
    private final FxRateService fxRates;
    private final Audit audit;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, ProjectRepository projects, PaymentRepository payments,
            CreditNoteRepository creditNotes, ClientRepository clients, DepositRepository deposits,
            RefundRepository refunds, SettingsService settings, FxRateService fxRates, Audit audit, Clock clock) {
        this.invoices = invoices;
        this.projects = projects;
        this.clients = clients;
        this.deposits = deposits;
        this.refunds = refunds;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.settings = settings;
        this.fxRates = fxRates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job");
        }
        List<Invoice> found = invoices.findByProjectIdOrderById(projectId);
        Map<Long, BigDecimal> paid = paidByInvoice(found);
        Map<Long, BigDecimal> credited = creditedByInvoice(found);
        return found.stream()
                .map(i -> InvoiceResponse.from(i, paid.getOrDefault(i.getId(), BigDecimal.ZERO),
                        credited.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .toList();
    }

    /**
     * A client's statement: every invoice across their projects with what is left to pay on each, and
     * the total they still owe, also grouped by job with a subtotal per job. Drafts are listed but not owed, as they have not been sent, nor are
     * void invoices, as they have been cancelled.
     */
    @Transactional(readOnly = true)
    public ClientStatementResponse statementForClient(Long clientId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        List<Invoice> found = invoices.findByClientIdWithProject(clientId);
        Map<Long, BigDecimal> paid = paidByInvoice(found);
        Map<Long, BigDecimal> credited = creditedByInvoice(found);
        List<ClientStatementResponse.Line> lines = found.stream()
                .map(i -> ClientStatementResponse.Line.from(i, paid.getOrDefault(i.getId(), BigDecimal.ZERO),
                        credited.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .toList();
        return ClientStatementResponse.from(client.getId(), client.getName(), client.getCurrency(), lines,
                accountEntries(clientId, found));
    }

    /**
     * What a client owed as at the end of the given day: the running account counting only the entries dated on or
     * before it.
     */
    @Transactional(readOnly = true)
    public ClientBalanceAsOfResponse balanceAsOf(Long clientId, LocalDate asOf) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        List<Invoice> found = invoices.findByClientIdWithProject(clientId);
        BigDecimal balance = accountEntries(clientId, found).stream()
                .filter(e -> e.date() != null && !e.date().isAfter(asOf))
                .map(StatementEntry::change)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new ClientBalanceAsOfResponse(client.getId(), client.getCurrency(), asOf, balance);
    }

    /**
     * How old a client's debt is as at today: what is left to pay on each owed invoice (not a draft, void or written
     * off) counted against how many days past its due date it is.
     */
    @Transactional(readOnly = true)
    public ClientAgingResponse agingForClient(Long clientId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        LocalDate today = LocalDate.now(clock);
        List<Invoice> found = invoices.findByClientIdWithProject(clientId);
        Map<Long, BigDecimal> paid = paidByInvoice(found);
        Map<Long, BigDecimal> credited = creditedByInvoice(found);
        BigDecimal current = BigDecimal.ZERO.setScale(2);
        BigDecimal days30To60 = BigDecimal.ZERO.setScale(2);
        BigDecimal days60Plus = BigDecimal.ZERO.setScale(2);
        for (Invoice invoice : found) {
            BigDecimal invoicePaid = paid.getOrDefault(invoice.getId(), BigDecimal.ZERO);
            BigDecimal invoiceCredited = credited.getOrDefault(invoice.getId(), BigDecimal.ZERO);
            InvoiceStatus status = invoice.statusFor(invoicePaid, invoiceCredited);
            if (status == InvoiceStatus.DRAFT || status.isClosedUnpaid()) {
                continue;
            }
            BigDecimal due = invoice.amountDue(invoicePaid, invoiceCredited);
            if (due.signum() <= 0) {
                continue;
            }
            long overdue = invoice.getDueDate() == null ? 0
                    : ChronoUnit.DAYS.between(invoice.getDueDate(), today);
            if (overdue > 60) {
                days60Plus = days60Plus.add(due);
            } else if (overdue > 30) {
                days30To60 = days30To60.add(due);
            } else {
                current = current.add(due);
            }
        }
        return new ClientAgingResponse(client.getId(), client.getCurrency(), today,
                current.setScale(2, RoundingMode.HALF_UP), days30To60.setScale(2, RoundingMode.HALF_UP),
                days60Plus.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The entries on a client's running account. Drafts have not been sent and void invoices were cancelled, so
     * neither is charged. A payment made out of held deposits is left out, as the deposit was already credited
     * when it was paid in.
     */
    private List<StatementEntry> accountEntries(Long clientId, List<Invoice> found) {
        List<StatementEntry> entries = new ArrayList<>();
        List<Long> sent = new ArrayList<>();
        for (Invoice invoice : found) {
            if (invoice.getStatus() == InvoiceStatus.DRAFT) {
                continue;
            }
            sent.add(invoice.getId());
            if (invoice.getStatus() != InvoiceStatus.VOID) {
                entries.add(StatementEntry.charge(StatementEntry.Kind.INVOICE, invoice.getId(), invoice.getId(),
                        invoice.getIssuedDate(), "Invoice #" + invoice.getId(), invoice.getAmount()));
            }
        }
        if (!sent.isEmpty()) {
            for (Payment payment : payments.findByInvoiceIdIn(sent)) {
                if (payment.getDepositApplicationId() == null) {
                    entries.add(StatementEntry.credit(StatementEntry.Kind.PAYMENT, payment.getId(), payment.getInvoiceId(),
                            payment.getDate(), "Payment on invoice #" + payment.getInvoiceId(), payment.getAmount()));
                }
            }
            for (CreditNote note : creditNotes.findByInvoiceIdIn(sent)) {
                entries.add(StatementEntry.credit(StatementEntry.Kind.CREDIT_NOTE, note.getId(), note.getInvoiceId(),
                        LocalDate.ofInstant(note.getIssuedAt(), ZoneOffset.UTC),
                        "Credit note on invoice #" + note.getInvoiceId(), note.getAmount()));
            }
        }
        for (Deposit deposit : deposits.findByClientIdOrderByDateAscIdAsc(clientId)) {
            entries.add(StatementEntry.credit(StatementEntry.Kind.DEPOSIT, deposit.getId(), null, deposit.getDate(),
                    "Deposit", deposit.getAmount()));
        }
        for (Refund refund : refunds.findByClientIdOrderByDateDescIdDesc(clientId)) {
            entries.add(StatementEntry.charge(StatementEntry.Kind.REFUND, refund.getId(), null, refund.getDate(),
                    "Refund", refund.getAmount()));
        }
        return entries;
    }

    private Map<Long, BigDecimal> paidByInvoice(List<Invoice> found) {
        return found.isEmpty() ? Map.of()
                : payments.sumAmountByInvoiceIds(found.stream().map(Invoice::getId).toList()).stream()
                        .collect(Collectors.toMap(PaymentRepository.InvoicePaidTotal::getInvoiceId,
                                PaymentRepository.InvoicePaidTotal::getPaid));
    }

    private Map<Long, BigDecimal> creditedByInvoice(List<Invoice> found) {
        return found.isEmpty() ? Map.of()
                : creditNotes.sumAmountByInvoiceIds(found.stream().map(Invoice::getId).toList()).stream()
                        .collect(Collectors.toMap(CreditNoteRepository.InvoiceCreditedTotal::getInvoiceId,
                                CreditNoteRepository.InvoiceCreditedTotal::getCredited));
    }

    /**
     * One page of the invoices across all projects, with the name of the project each one is for,
     * narrowed to the given status when one is supplied. Pages are numbered from zero.
     */
    @Transactional(readOnly = true)
    public InvoicePageResponse listAll(InvoiceStatus status, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<Invoice> found = status == null ? invoices.findPageWithProject(request)
                : invoices.findPageWithProjectByStatus(status, request);
        return InvoicePageResponse.from(found);
    }

    @Transactional
    public InvoiceResponse create(Long projectId, InvoiceRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        if (project.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The job is closed; no new invoice can be raised on it");
        }
        LocalDate issued = request.issuedDate() != null ? request.issuedDate() : LocalDate.now();
        LocalDate due = request.dueDate() != null ? request.dueDate() : issued.plusDays(InvoiceRequest.DEFAULT_TERM_DAYS);
        if (due.isBefore(issued)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Due date must not be before the issue date");
        }
        Invoice invoice = new Invoice(project, issued, due);
        if (request.amount() != null) {
            invoice.addLineItem(InvoiceRequest.SINGLE_AMOUNT_DESCRIPTION, BigDecimal.ONE, null, request.amount());
        }
        invoice.applyTax(request.taxPct() != null ? request.taxPct() : settings.defaultTaxPct());
        // A new invoice starts with the client's standard discount, if they have one.
        BigDecimal defaultDiscountPct = project.getClient().getDefaultDiscountPct();
        if (defaultDiscountPct.signum() > 0) {
            invoice.addDiscount(defaultDiscountPct, BigDecimal.ZERO);
        }
        Invoice saved = invoices.save(invoice);
        return InvoiceResponse.from(saved, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** A single invoice with its line items. */
    @Transactional(readOnly = true)
    public InvoiceDetailResponse get(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        return detail(invoice, invoice.statusFor(payments.sumAmountByInvoiceId(invoiceId),
                creditNotes.sumAmountByInvoiceId(invoiceId)));
    }

    /** Adds a line item to a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse addLineItem(Long projectId, Long invoiceId, LineItemRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.addLineItem(request.description().strip(), request.qty(), request.normalizedUnit(), request.unitPrice(),
                request.exempt(), request.lineDiscountPct());
        invoices.flush();
        return detail(invoice);
    }

    /** Changes a line item on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse updateLineItem(Long projectId, Long invoiceId, Long lineItemId,
            LineItemRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        InvoiceLineItem item = findLineItem(invoice, lineItemId);
        invoice.updateLineItem(item, request.description().strip(), request.qty(), request.normalizedUnit(),
                request.unitPrice(), request.exempt(), request.lineDiscountPct());
        invoices.flush();
        return detail(invoice);
    }

    /** Removes a line item from a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse removeLineItem(Long projectId, Long invoiceId, Long lineItemId) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.removeLineItem(findLineItem(invoice, lineItemId));
        invoices.flush();
        return detail(invoice);
    }

    /** Replaces the discounts on a draft invoice with a single percentage or flat one and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse applyDiscount(Long projectId, Long invoiceId, DiscountRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyDiscount(request.discountPct(), request.flatAmount(), request.discountCap());
        invoices.flush();
        return detail(invoice);
    }

    /** Adds another percentage or flat discount to a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse addDiscount(Long projectId, Long invoiceId, DiscountRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        if (request.discountPct().signum() == 0 && request.flatAmount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A discount takes off a percentage or a flat amount");
        }
        invoice.addDiscount(request.discountPct(), request.flatAmount(), request.discountCap());
        invoices.flush();
        return detail(invoice);
    }

    /** Removes one discount from a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse removeDiscount(Long projectId, Long invoiceId, Long discountId) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.removeDiscount(invoice.findDiscount(discountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Discount not found")));
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the sales tax percentage on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse applyTax(Long projectId, Long invoiceId, TaxRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyTax(request.taxPct());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the levy (second tax) percentage on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse applyLevy(Long projectId, Long invoiceId, LevyRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyLevy(request.levyPct());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the flat surcharge on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse applySurcharge(Long projectId, Long invoiceId, SurchargeRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applySurcharge(request.surcharge());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the minimum charge on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse applyMinimumCharge(Long projectId, Long invoiceId, MinimumChargeRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyMinimumCharge(request.minimumCharge());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the early-payment discount offered on a draft invoice. */
    @Transactional
    public InvoiceDetailResponse applyEarlyPayment(Long projectId, Long invoiceId, EarlyPaymentRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyEarlyPayment(request.earlyPaymentPct(), request.earlyPaymentDays());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the late fee charged for each day a draft invoice is overdue once it has been sent. */
    @Transactional
    public InvoiceDetailResponse applyLateFee(Long projectId, Long invoiceId, LateFeeRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyLateFee(request.lateFeePerDay());
        invoices.flush();
        return detail(invoice);
    }

    /** Sends a draft invoice and records the sending in the audit log. */
    @Transactional
    public InvoiceResponse send(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be sent");
        }
        if (invoice.getAmount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Add a line item before sending the invoice");
        }
        invoice.markSent();
        invoices.flush();
        audit.record("INVOICE_SENT id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return toResponse(invoice);
    }

    /**
     * Cancels an invoice sent by mistake, so it no longer counts toward what is owed, and records the
     * cancellation in the audit log. Only a sent invoice with nothing yet paid against it can be voided.
     */
    @Transactional
    public InvoiceResponse cancel(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.SENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a sent invoice with no payments recorded can be cancelled");
        }
        invoice.markVoid();
        invoices.flush();
        audit.record("INVOICE_VOIDED id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return toResponse(invoice);
    }

    /**
     * Writes off a sent or part-paid invoice as bad debt, so what is left on it no longer counts toward what is
     * owed while the invoice stays on record, and records the write-off (the balance given up) in the audit log.
     */
    @Transactional
    public InvoiceDetailResponse writeOff(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        BigDecimal paid = payments.sumAmountByInvoiceId(invoiceId);
        BigDecimal credited = creditNotes.sumAmountByInvoiceId(invoiceId);
        if (!invoice.statusFor(paid, credited).isOwing()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a sent invoice with something still owed can be written off");
        }
        BigDecimal balance = invoice.amountDue(paid, credited);
        invoice.markWrittenOff();
        invoices.flush();
        audit.record("INVOICE_WRITTEN_OFF id=" + invoice.getId() + " amount=" + balance.setScale(2).toPlainString());
        return detail(invoice);
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return InvoiceResponse.from(invoice, payments.sumAmountByInvoiceId(invoice.getId()),
                creditNotes.sumAmountByInvoiceId(invoice.getId()));
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }

    private Invoice findDraft(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be changed");
        }
        return invoice;
    }

    private static InvoiceLineItem findLineItem(Invoice invoice, Long lineItemId) {
        return invoice.findLineItem(lineItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown line item"));
    }

    private InvoiceDetailResponse detail(Invoice invoice) {
        return detail(invoice, invoice.getStatus());
    }

    /**
     * The invoice in full; a foreign invoice also converted into the home currency at the exchange rate
     * from its issue date, so a later rate never changes what it was worth.
     */
    private InvoiceDetailResponse detail(Invoice invoice, InvoiceStatus status) {
        String home = settings.homeCurrency();
        String currency = invoice.getProject().getClient().getCurrency();
        BigDecimal homeAmount = currency.equals(home) ? null
                : fxRates.toHome(currency, invoice.getIssuedDate(), invoice.getAmount()).orElse(null);
        return InvoiceDetailResponse.from(invoice, status, home, homeAmount, LocalDate.now(clock));
    }
}
