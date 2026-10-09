package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.client.ClientService;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.currency.Currency;
import net.officefloor.hq.app.fx.FxRateService;
import net.officefloor.hq.app.invoice.Invoice;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardService {

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final InvoiceRepository invoices;
    private final ClientService clientService;
    private final PaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final SettingsService settings;
    private final FxRateService fxRates;
    private final Clock clock;

    /** How many of the biggest debtors the dashboard lists. */
    static final int TOP_CLIENTS = 5;

    public DashboardService(ClientRepository clients, ProjectRepository projects, InvoiceRepository invoices,
            ClientService clientService, PaymentRepository payments, CreditNoteRepository creditNotes,
            SettingsService settings, FxRateService fxRates, Clock clock) {
        this.clients = clients;
        this.projects = projects;
        this.invoices = invoices;
        this.clientService = clientService;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.settings = settings;
        this.fxRates = fxRates;
        this.clock = clock;
    }

    /**
     * Counts of clients and projects, and the total still owed in each currency (what is left to pay
     * on sent invoices that are not yet fully paid; a currency with written-off invoices is listed even when nothing is owed), plus how many of those sent invoices are past their due date and
     * what is overdue on them (see {@link #overdueAmount}), and the top clients ranked by what they owe.
     */
    @Transactional(readOnly = true)
    public DashboardResponse summary() {
        List<InvoiceStatus> owing = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL);
        Map<String, BigDecimal> owed = new TreeMap<>(Currency.ORDER);
        // A currency whose debts were written off stays listed, showing nothing owed, so the write-off is visible.
        clients.findAllById(invoices.sumAmountByStatusInPerClient(List.of(InvoiceStatus.WRITTEN_OFF)).stream()
                .map(InvoiceRepository.ClientTotal::getClientId).toList())
                .forEach(c -> owed.put(c.getCurrency(), BigDecimal.ZERO));
        owed.putAll(clientService.outstandingByCurrency());
        List<DashboardResponse.CurrencyTotal> outstanding = owed.entrySet().stream()
                .map(e -> new DashboardResponse.CurrencyTotal(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP)))
                .toList();
        LocalDate today = LocalDate.now(clock);
        long overdue = invoices.countByStatusInAndDueDateBefore(owing, today);
        String home = settings.homeCurrency();
        BigDecimal overdueAmount = overdueAmount(invoices.findByStatusInAndDueDateBefore(owing, today), home, today);
        List<DashboardResponse.TopClient> top = clientService.topByOutstanding(TOP_CLIENTS).stream()
                .map(c -> new DashboardResponse.TopClient(c.id(), c.name(), c.currency(), c.outstanding()))
                .toList();
        return new DashboardResponse(clients.count(), projects.count(), outstanding, overdue, home, overdueAmount, top);
    }

    /**
     * What is overdue, in the home currency: what is left to pay on each overdue invoice plus the late fee it
     * has accrued by today. A foreign invoice converts at the exchange rate from its issue date; one whose
     * currency has no rate by then cannot be converted and is left out.
     */
    private BigDecimal overdueAmount(List<Invoice> found, String home, LocalDate today) {
        if (found.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        List<Long> ids = found.stream().map(Invoice::getId).toList();
        Map<Long, BigDecimal> paid = payments.sumAmountByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(PaymentRepository.InvoicePaidTotal::getInvoiceId,
                        PaymentRepository.InvoicePaidTotal::getPaid));
        Map<Long, BigDecimal> credited = creditNotes.sumAmountByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(CreditNoteRepository.InvoiceCreditedTotal::getInvoiceId,
                        CreditNoteRepository.InvoiceCreditedTotal::getCredited));
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : found) {
            BigDecimal due = invoice.amountDue(paid.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                    credited.getOrDefault(invoice.getId(), BigDecimal.ZERO))
                    .add(invoice.lateFee(invoice.getStatus(), today));
            String currency = invoice.getProject().getClient().getCurrency();
            BigDecimal inHome = currency.equals(home) ? due
                    : fxRates.toHome(currency, invoice.getIssuedDate(), due).orElse(BigDecimal.ZERO);
            total = total.add(inHome);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The tax charged over a period, in the home currency: the sales tax and levy on every invoice issued on or
     * between the given dates that was actually charged (sent, whether paid or not, or later written off; drafts
     * and cancelled invoices are left out). A foreign invoice converts at the exchange rate from its issue date;
     * one whose currency has no rate by then cannot be converted and is left out.
     */
    @Transactional(readOnly = true)
    public TaxSummaryResponse taxSummary(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date");
        }
        List<InvoiceStatus> charged = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID,
                InvoiceStatus.WRITTEN_OFF);
        String home = settings.homeCurrency();
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal levy = BigDecimal.ZERO;
        long count = 0;
        for (Invoice invoice : invoices.findByStatusInAndIssuedDateBetween(charged, from, to)) {
            String currency = invoice.getProject().getClient().getCurrency();
            if (currency.equals(home)) {
                tax = tax.add(invoice.getTax());
                levy = levy.add(invoice.getLevy());
                count++;
                continue;
            }
            var inHomeTax = fxRates.toHome(currency, invoice.getIssuedDate(), invoice.getTax());
            var inHomeLevy = fxRates.toHome(currency, invoice.getIssuedDate(), invoice.getLevy());
            if (inHomeTax.isPresent() && inHomeLevy.isPresent()) {
                tax = tax.add(inHomeTax.get());
                levy = levy.add(inHomeLevy.get());
                count++;
            }
        }
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        levy = levy.setScale(2, RoundingMode.HALF_UP);
        return new TaxSummaryResponse(from, to, home, count, tax, levy, tax.add(levy));
    }
}
