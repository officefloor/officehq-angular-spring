package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.client.ClientService;
import net.officefloor.hq.app.creditnote.CreditNote;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.currency.CurrencyService;
import net.officefloor.hq.app.currency.MoneyRule;
import net.officefloor.hq.app.deposit.Deposit;
import net.officefloor.hq.app.deposit.DepositRepository;
import net.officefloor.hq.app.fx.FxRateService;
import net.officefloor.hq.app.instalment.Instalment;
import net.officefloor.hq.app.instalment.InstalmentRepository;
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
    private final InstalmentRepository instalments;
    private final ClientService clientService;
    private final CurrencyService currencies;
    private final Audit audit;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, ProjectRepository projects, PaymentRepository payments,
            CreditNoteRepository creditNotes, ClientRepository clients, DepositRepository deposits,
            RefundRepository refunds, SettingsService settings, FxRateService fxRates, InstalmentRepository instalments,
            ClientService clientService, CurrencyService currencies, Audit audit, Clock clock) {
        this.clientService = clientService;
        this.currencies = currencies;
        this.invoices = invoices;
        this.projects = projects;
        this.clients = clients;
        this.deposits = deposits;
        this.refunds = refunds;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.settings = settings;
        this.fxRates = fxRates;
        this.instalments = instalments;
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
        Map<Long, List<Instalment>> schedules = instalments
                .findByInvoiceIdIn(found.stream().map(Invoice::getId).toList()).stream()
                .collect(Collectors.groupingBy(Instalment::getInvoiceId));
        LocalDate today = LocalDate.now(clock);
        return found.stream()
                .map(i -> {
                    BigDecimal invoicePaid = paid.getOrDefault(i.getId(), BigDecimal.ZERO);
                    BigDecimal invoiceCredited = credited.getOrDefault(i.getId(), BigDecimal.ZERO);
                    ScheduleStatus schedule = scheduleStatus(i.statusFor(invoicePaid, invoiceCredited),
                            schedules.getOrDefault(i.getId(), List.of()), today);
                    return InvoiceResponse.from(i, invoicePaid, invoiceCredited, schedule);
                })
                .toList();
    }

    /**
     * How an invoice is keeping to its instalment plan: only an owing invoice with instalments has one. It is BEHIND
     * once an unpaid instalment is past its due date, otherwise ON_TRACK.
     */
    private static ScheduleStatus scheduleStatus(InvoiceStatus status, List<Instalment> schedule, LocalDate today) {
        if (!status.isOwing() || schedule.isEmpty()) {
            return null;
        }
        boolean overdue = schedule.stream().anyMatch(n -> !n.isPaid() && n.getDueDate().isBefore(today));
        return overdue ? ScheduleStatus.BEHIND : ScheduleStatus.ON_TRACK;
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
        Function<String, MoneyRule> rules = moneyRules();
        List<ClientStatementResponse.Line> lines = found.stream()
                .map(i -> ClientStatementResponse.Line.from(i, paid.getOrDefault(i.getId(), BigDecimal.ZERO),
                        credited.getOrDefault(i.getId(), BigDecimal.ZERO), rules.apply(i.getCurrency())))
                .toList();
        String home = settings.homeCurrency();
        MoneyRule homeRule = rules.apply(home);
        return ClientStatementResponse.from(client.getId(), client.getName(), client.getCurrency(), lines,
                accountEntries(clientId, found, rules), home, l -> l.issuedDate() == null ? Optional.empty()
                        : fxRates.convert(l.currency(), home, l.issuedDate(), l.amountDue()).map(homeRule::round));
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
        BigDecimal balance = accountEntries(clientId, found, moneyRules()).stream()
                .filter(e -> e.date() != null && !e.date().isAfter(asOf))
                .map(StatementEntry::change)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new ClientBalanceAsOfResponse(client.getId(), client.getCurrency(), asOf, balance);
    }

    /**
     * A client's statement for the given date range (both ends included): the balance owed at the start of it, the
     * entries dated within it in date order each carrying the running balance, the net movement within it, and the
     * balance owed at its end (always the opening balance plus the movements). The payments received within it are
     * also listed and totalled on their own, as are the credit notes issued within it.
     */
    @Transactional(readOnly = true)
    public ClientStatementRangeResponse statementForRange(Long clientId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The range must end on or after its start");
        }
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        List<StatementEntry> dated = accountEntries(clientId, invoices.findByClientIdWithProject(clientId), moneyRules()).stream()
                .filter(e -> e.date() != null)
                .sorted(StatementEntry.DATE_ORDER)
                .toList();
        BigDecimal opening = dated.stream()
                .filter(e -> e.date().isBefore(from))
                .map(StatementEntry::change)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal movements = BigDecimal.ZERO.setScale(2);
        List<StatementEntry> within = new ArrayList<>();
        for (StatementEntry entry : dated) {
            if (!entry.date().isBefore(from) && !entry.date().isAfter(to)) {
                movements = movements.add(entry.change());
                within.add(entry.withBalance(opening.add(movements)));
            }
        }
        List<StatementEntry> paymentsWithin = within.stream()
                .filter(e -> e.kind() == StatementEntry.Kind.PAYMENT)
                .toList();
        BigDecimal paymentsTotal = paymentsWithin.stream()
                .map(StatementEntry::credit)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        List<StatementEntry> creditsWithin = within.stream()
                .filter(e -> e.kind() == StatementEntry.Kind.CREDIT_NOTE)
                .toList();
        BigDecimal creditsTotal = creditsWithin.stream()
                .map(StatementEntry::credit)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new ClientStatementRangeResponse(client.getId(), client.getCurrency(), from, to, opening, within,
                movements, opening.add(movements), paymentsWithin, paymentsTotal, creditsWithin, creditsTotal);
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
        Function<String, MoneyRule> rules = moneyRules();
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
            BigDecimal due = rules.apply(invoice.getCurrency()).round(invoice.amountDue(invoicePaid, invoiceCredited));
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
     * when it was paid in. Each amount is rounded by the rule of the currency it is in, as the invoice is, so the
     * balances add up to the cent.
     */
    private List<StatementEntry> accountEntries(Long clientId, List<Invoice> found, Function<String, MoneyRule> rules) {
        MoneyRule clientRule = clients.findById(clientId).map(c -> rules.apply(c.getCurrency())).orElse(MoneyRule.CENTS);
        Map<Long, MoneyRule> invoiceRules = new HashMap<>();
        found.forEach(i -> invoiceRules.put(i.getId(), rules.apply(i.getCurrency())));
        List<StatementEntry> entries = new ArrayList<>();
        List<Long> sent = new ArrayList<>();
        Map<Long, String> poNumbers = new HashMap<>();
        for (Invoice invoice : found) {
            if (invoice.getStatus() == InvoiceStatus.DRAFT) {
                continue;
            }
            sent.add(invoice.getId());
            if (invoice.getPoNumber() != null) {
                poNumbers.put(invoice.getId(), invoice.getPoNumber());
            }
            if (invoice.getStatus() != InvoiceStatus.VOID) {
                entries.add(StatementEntry.charge(StatementEntry.Kind.INVOICE, invoice.getId(), invoice.getId(),
                        invoice.getIssuedDate(), "Invoice #" + invoice.getId(), invoiceRules.get(invoice.getId()).round(invoice.getAmount()),
                        invoice.getPoNumber()));
            }
        }
        if (!sent.isEmpty()) {
            for (Payment payment : payments.findByInvoiceIdIn(sent)) {
                if (payment.getDepositApplicationId() == null) {
                    entries.add(StatementEntry.credit(StatementEntry.Kind.PAYMENT, payment.getId(), payment.getInvoiceId(),
                            payment.getDate(), "Payment on invoice #" + payment.getInvoiceId(),
                            invoiceRules.get(payment.getInvoiceId()).round(payment.getAmount()),
                            poNumbers.get(payment.getInvoiceId())));
                }
            }
            for (CreditNote note : creditNotes.findByInvoiceIdIn(sent)) {
                entries.add(StatementEntry.credit(StatementEntry.Kind.CREDIT_NOTE, note.getId(), note.getInvoiceId(),
                        LocalDate.ofInstant(note.getIssuedAt(), ZoneOffset.UTC),
                        "Credit note on invoice #" + note.getInvoiceId(),
                        invoiceRules.get(note.getInvoiceId()).round(note.getAmount()),
                        poNumbers.get(note.getInvoiceId())));
            }
        }
        for (Deposit deposit : deposits.findByClientIdOrderByDateAscIdAsc(clientId)) {
            entries.add(StatementEntry.credit(StatementEntry.Kind.DEPOSIT, deposit.getId(), null, deposit.getDate(),
                    "Deposit", clientRule.round(deposit.getAmount()), null));
        }
        for (Refund refund : refunds.findByClientIdOrderByDateDescIdDesc(clientId)) {
            entries.add(StatementEntry.charge(StatementEntry.Kind.REFUND, refund.getId(), null, refund.getDate(),
                    "Refund", clientRule.round(refund.getAmount()), null));
        }
        return entries;
    }

    /** Looks up each currency's rounding rule once per request. */
    private Function<String, MoneyRule> moneyRules() {
        Map<String, MoneyRule> known = new HashMap<>();
        return code -> known.computeIfAbsent(code, currencies::ruleFor);
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
        // Without a due date the invoice falls due after the client's payment terms (net N days).
        LocalDate due = request.dueDate() != null ? request.dueDate()
                : issued.plusDays(InvoiceRequest.termDays(project.getClient().getPaymentTermsDays()));
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
        invoice.applyEarlyPayment(request.earlyPaymentPct());
        invoices.flush();
        return detail(invoice);
    }

    /** Sets the settlement rebate offered on a draft invoice for paying before the due date. */
    @Transactional
    public InvoiceDetailResponse applyRebate(Long projectId, Long invoiceId, RebateRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyRebate(request.rebatePct());
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

    /** Sets the percentage of a draft invoice held back as retention, not due yet. */
    @Transactional
    public InvoiceDetailResponse applyRetention(Long projectId, Long invoiceId, RetentionRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.applyRetention(request.retentionPct());
        invoices.flush();
        return detail(invoice);
    }

    /**
     * Puts (or, given none, clears) the client's purchase-order number on an invoice, recording the change in the
     * audit log.
     */
    @Transactional
    public InvoiceDetailResponse setPoNumber(Long projectId, Long invoiceId, String poNumber) {
        String po = poNumber == null || poNumber.isBlank() ? null : poNumber.trim();
        Invoice invoice = find(projectId, invoiceId);
        if (!Objects.equals(invoice.getPoNumber(), po)) {
            invoice.setPoNumber(po);
            invoices.flush();
            audit.record("INVOICE_PO_NUMBER_SET id=" + invoice.getId() + " poNumber=" + (po == null ? "none" : po));
        }
        return detail(invoice);
    }

    /**
     * Releases the retention held back on a sent invoice once the job is finished, so it becomes due, and
     * records the release in the audit log.
     */
    @Transactional
    public InvoiceDetailResponse releaseRetention(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT || invoice.getStatus().isClosedUnpaid()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a sent invoice can have its retention released");
        }
        BigDecimal retention = invoice.getRetention();
        if (retention.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invoice has no retention held back to release");
        }
        invoice.releaseRetention();
        invoices.flush();
        audit.record("INVOICE_RETENTION_RELEASED id=" + invoice.getId() + " amount=" + retention.toPlainString());
        return detail(invoice);
    }

    /**
     * Sends a draft invoice and records the sending in the audit log. An invoice that would take the
     * client over their credit limit is refused and stays a draft.
     */
    @Transactional
    public InvoiceResponse send(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be sent");
        }
        if (invoice.getAmount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Add a line item before sending the invoice");
        }
        Client client = invoice.getProject().getClient();
        BigDecimal limit = client.getCreditLimit();
        if (limit != null && clientService.outstanding(client.getId()).add(invoice.getAmount()).compareTo(limit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Sending this invoice would take the client over their credit limit");
        }
        invoice.markSent();
        invoices.flush();
        audit.record("INVOICE_SENT id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return toResponse(invoice);
    }

    /**
     * Sends every draft invoice on a job in one go, and records each sending in the audit log. It is all or
     * nothing: if any draft has no line items, or sending them all would take the client over their credit
     * limit, none are sent.
     */
    @Transactional
    public List<InvoiceResponse> sendDrafts(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job");
        }
        List<Invoice> drafts = invoices.findByProjectIdOrderById(projectId).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.DRAFT)
                .toList();
        if (drafts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There are no draft invoices to send");
        }
        if (drafts.stream().anyMatch(i -> i.getAmount().signum() == 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Add a line item to every draft before sending");
        }
        Client client = drafts.get(0).getProject().getClient();
        BigDecimal limit = client.getCreditLimit();
        BigDecimal total = drafts.stream().map(Invoice::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (limit != null && clientService.outstanding(client.getId()).add(total).compareTo(limit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Sending these invoices would take the client over their credit limit");
        }
        drafts.forEach(Invoice::markSent);
        invoices.flush();
        drafts.forEach(i -> audit.record("INVOICE_SENT id=" + i.getId() + " amount=" + i.getAmount().toPlainString()));
        return drafts.stream().map(this::toResponse).toList();
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
     * Flags a sent or part-paid invoice as disputed by the client and records it in the audit log. The invoice still
     * counts as owed; the flag only marks it.
     */
    @Transactional
    public InvoiceResponse dispute(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (!invoice.getStatus().isOwing()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an owing invoice can be disputed");
        }
        if (!invoice.isDisputed()) {
            invoice.markDisputed();
            invoices.flush();
            audit.record("INVOICE_DISPUTED id=" + invoice.getId());
        }
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

    /**
     * Writes off part of a sent or part-paid invoice as bad debt, so that part no longer counts toward what is
     * owed while the rest still does, and records the part given up in the audit log. The invoice's status is then
     * worked out from what is still owed after the write-off. Writing off everything still owed writes off the
     * whole invoice.
     */
    @Transactional
    public InvoiceDetailResponse writeOffPart(Long projectId, Long invoiceId, PartialWriteOffRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        BigDecimal paid = payments.sumAmountByInvoiceId(invoiceId);
        BigDecimal credited = creditNotes.sumAmountByInvoiceId(invoiceId);
        if (!invoice.statusFor(paid, credited).isOwing()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a sent invoice with something still owed can be written off");
        }
        BigDecimal balance = invoice.amountDue(paid, credited);
        int comparison = request.amount().compareTo(balance);
        if (comparison > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Cannot write off more than is still owed");
        }
        if (comparison == 0) {
            return writeOff(projectId, invoiceId);
        }
        invoice.writeOffPart(request.amount());
        invoice.applySettledTotals(paid, credited);
        invoices.flush();
        audit.record("INVOICE_PART_WRITTEN_OFF id=" + invoice.getId() + " amount="
                + request.amount().setScale(2).toPlainString());
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
        String currency = invoice.getCurrency();
        BigDecimal homeAmount = currency.equals(home) ? null
                : fxRates.toHome(currency, invoice.getIssuedDate(), invoice.getAmount()).orElse(null);
        BigDecimal amountDue = invoice.dueNow(payments.sumAmountByInvoiceId(invoice.getId()),
                creditNotes.sumAmountByInvoiceId(invoice.getId()));
        BigDecimal fxGainLoss = currency.equals(home) ? null : fxGainLoss(invoice, currency);
        return InvoiceDetailResponse.from(invoice, status, home, homeAmount, LocalDate.now(clock), amountDue, fxGainLoss);
    }

    /**
     * The exchange gain (positive) or loss (negative) in the home currency realised by the payments on a foreign
     * invoice: each payment's amount is worth its value at the rate on the payment's date less its value at the
     * rate on the invoice's issue date. Null when there are no payments or a rate is missing.
     */
    private BigDecimal fxGainLoss(Invoice invoice, String currency) {
        List<Payment> paid = payments.findByInvoiceIdOrderByDateAscIdAsc(invoice.getId());
        if (paid.isEmpty() || invoice.getIssuedDate() == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Payment payment : paid) {
            Optional<BigDecimal> atIssue = fxRates.toHome(currency, invoice.getIssuedDate(), payment.getAmount());
            Optional<BigDecimal> atPayment = fxRates.toHome(currency, payment.getDate(), payment.getAmount());
            if (atIssue.isEmpty() || atPayment.isEmpty()) {
                return null;
            }
            total = total.add(atPayment.get().subtract(atIssue.get()));
        }
        return total;
    }
}
