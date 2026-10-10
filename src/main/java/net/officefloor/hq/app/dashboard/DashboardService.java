package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.client.ClientService;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.currency.Currency;
import net.officefloor.hq.app.fx.FxRateService;
import net.officefloor.hq.app.instalment.Instalment;
import net.officefloor.hq.app.instalment.InstalmentRepository;
import net.officefloor.hq.app.invoice.Invoice;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.task.TaskRepository;
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
    private final InstalmentRepository instalments;
    private final TaskRepository tasks;
    private final Clock clock;

    /** How many of the biggest debtors the dashboard lists. */
    static final int TOP_CLIENTS = 5;

    public DashboardService(ClientRepository clients, ProjectRepository projects, InvoiceRepository invoices,
            ClientService clientService, PaymentRepository payments, CreditNoteRepository creditNotes,
            SettingsService settings, FxRateService fxRates, InstalmentRepository instalments, TaskRepository tasks,
            Clock clock) {
        this.clients = clients;
        this.projects = projects;
        this.invoices = invoices;
        this.clientService = clientService;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.settings = settings;
        this.fxRates = fxRates;
        this.instalments = instalments;
        this.tasks = tasks;
        this.clock = clock;
    }

    /**
     * Counts of clients and projects, and the total still owed in each currency (what is left to pay
     * on sent invoices that are not yet fully paid; a currency with written-off invoices is listed even when nothing is owed), plus how many of those sent invoices (not disputed) are past their due date and
     * what is overdue on them (in total and split by how many days overdue each invoice is), what is outstanding as one grand total in the home currency (see {@link #inHome}), and the top clients ranked by what they owe converted into the home currency (a client none of whose debt can be converted is left out), and how many tasks not yet done are past their due date.
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
        Map<Invoice, BigDecimal> owedInHome = inHome(invoices.findByStatusInWithClient(owing), home, today, false);
        BigDecimal outstandingHome = sum(owedInHome);
        Map<Invoice, BigDecimal> overdueInHome = inHome(invoices.findByStatusInAndDueDateBefore(owing, today), home, today, true);
        BigDecimal overdueAmount = sum(overdueInHome);
        DashboardResponse.OverdueBuckets overdueBuckets = buckets(overdueInHome, today);
        Map<Long, BigDecimal> clientsInHome = new HashMap<>();
        owedInHome.forEach((invoice, amount) -> clientsInHome.merge(invoice.getProject().getClient().getId(), amount,
                BigDecimal::add));
        List<DashboardResponse.TopClient> top = clientService.topByOutstanding(TOP_CLIENTS, clientsInHome).stream()
                .map(c -> new DashboardResponse.TopClient(c.id(), c.name(), c.currency(), c.outstanding(),
                        clientsInHome.get(c.id()).setScale(2, RoundingMode.HALF_UP)))
                .toList();
        return new DashboardResponse(clients.count(), projects.count(), outstanding, outstandingHome, overdue, home, overdueAmount,
                overdueBuckets, top, tasks.countByDoneFalseAndDueDateBefore(today));
    }

    /**
     * Splits what is overdue by how many days past its due date each invoice is: up to 30 days, 31 to 60 days,
     * and more than 60 days.
     */
    private static DashboardResponse.OverdueBuckets buckets(Map<Invoice, BigDecimal> overdueInHome, LocalDate today) {
        BigDecimal upTo30 = BigDecimal.ZERO;
        BigDecimal days31To60 = BigDecimal.ZERO;
        BigDecimal days60Plus = BigDecimal.ZERO;
        for (Map.Entry<Invoice, BigDecimal> e : overdueInHome.entrySet()) {
            long days = ChronoUnit.DAYS.between(e.getKey().getDueDate(), today);
            if (days > 60) {
                days60Plus = days60Plus.add(e.getValue());
            } else if (days > 30) {
                days31To60 = days31To60.add(e.getValue());
            } else {
                upTo30 = upTo30.add(e.getValue());
            }
        }
        return new DashboardResponse.OverdueBuckets(upTo30.setScale(2, RoundingMode.HALF_UP),
                days31To60.setScale(2, RoundingMode.HALF_UP), days60Plus.setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal sum(Map<Invoice, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * What is left to pay on each of the invoices, in the home currency, with the late fee and the interest on late
     * instalments each has built up by today when asked for (as for what is overdue). A foreign invoice converts at
     * the exchange rate from its issue date; one whose currency has no rate by then cannot be converted and is left out.
     */
    private Map<Invoice, BigDecimal> inHome(List<Invoice> found, String home, LocalDate today, boolean withCharges) {
        Map<Invoice, BigDecimal> amounts = new LinkedHashMap<>();
        if (found.isEmpty()) {
            return amounts;
        }
        List<Long> ids = found.stream().map(Invoice::getId).toList();
        Map<Long, BigDecimal> paid = payments.sumAmountByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(PaymentRepository.InvoicePaidTotal::getInvoiceId,
                        PaymentRepository.InvoicePaidTotal::getPaid));
        Map<Long, BigDecimal> credited = creditNotes.sumAmountByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(CreditNoteRepository.InvoiceCreditedTotal::getInvoiceId,
                        CreditNoteRepository.InvoiceCreditedTotal::getCredited));
        Map<Long, List<Instalment>> scheduled = withCharges
                ? instalments.findByInvoiceIdIn(ids).stream().collect(Collectors.groupingBy(Instalment::getInvoiceId))
                : Map.of();
        for (Invoice invoice : found) {
            BigDecimal due = invoice.amountDue(paid.getOrDefault(invoice.getId(), BigDecimal.ZERO),
                    credited.getOrDefault(invoice.getId(), BigDecimal.ZERO));
            if (withCharges) {
                due = due.add(invoice.lateFee(invoice.getStatus(), today))
                        .add(interest(invoice, scheduled.getOrDefault(invoice.getId(), List.of()), today));
            }
            String currency = invoice.getProject().getClient().getCurrency();
            BigDecimal inHome = currency.equals(home) ? due
                    : fxRates.toHome(currency, invoice.getIssuedDate(), due).orElse(null);
            if (inHome != null) {
                amounts.put(invoice, inHome);
            }
        }
        return amounts;
    }

    /** The interest built up by today on an invoice's instalments still unpaid past their due dates. */
    private static BigDecimal interest(Invoice invoice, List<Instalment> scheduled, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (Instalment instalment : scheduled) {
            if (instalment.isPaid()) {
                continue;
            }
            long daysLate = Math.max(0, ChronoUnit.DAYS.between(instalment.getDueDate(), today));
            total = total.add(invoice.getInstalmentInterestPerDay().multiply(BigDecimal.valueOf(daysLate))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return total;
    }

    /**
     * The money expected in from scheduled instalments: every instalment not yet paid on an invoice that has been
     * sent and is not yet fully paid, earliest due first, with the total in the home currency. A foreign instalment
     * converts at the exchange rate from its invoice's issue date; one whose currency has no rate by then is still
     * listed but left out of the total.
     */
    @Transactional(readOnly = true)
    public ForecastResponse forecast() {
        Map<Long, Invoice> open = invoices.findByStatusInWithClient(List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL))
                .stream().collect(Collectors.toMap(Invoice::getId, i -> i));
        String home = settings.homeCurrency();
        List<Instalment> due = open.isEmpty() ? List.of()
                : instalments.findByInvoiceIdIn(open.keySet()).stream().filter(i -> !i.isPaid())
                        .sorted(Comparator.comparing(Instalment::getDueDate).thenComparing(Instalment::getId))
                        .toList();
        BigDecimal total = BigDecimal.ZERO;
        List<ForecastResponse.Entry> entries = new ArrayList<>();
        for (Instalment instalment : due) {
            Invoice invoice = open.get(instalment.getInvoiceId());
            String currency = invoice.getCurrency();
            BigDecimal inHome = currency.equals(home) ? instalment.getAmount()
                    : fxRates.toHome(currency, invoice.getIssuedDate(), instalment.getAmount()).orElse(BigDecimal.ZERO);
            total = total.add(inHome);
            entries.add(new ForecastResponse.Entry(instalment.getId(), instalment.getDueDate(), instalment.getAmount(),
                    currency, invoice.getId(), invoice.getProject().getId(), invoice.getProject().getName(),
                    invoice.getProject().getClient().getName()));
        }
        return new ForecastResponse(home, entries, total.setScale(2, RoundingMode.HALF_UP));
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

    /**
     * The revenue billed on or between the given dates (over all time when either date is missing): the amounts of
     * the invoices issued in the range that were sent (drafts and cancelled invoices are left out), each converted
     * into the home currency at its issue date's rate (an invoice that cannot be converted is left out). The revenue
     * is also broken down by job (project), highest-earning first, and by the month each invoice was issued in, earliest first, to show the trend.
     */
    @Transactional(readOnly = true)
    public RevenueReportResponse revenueReport(LocalDate from, LocalDate to) {
        boolean allTime = from == null || to == null;
        if (!allTime && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date");
        }
        List<InvoiceStatus> billed = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID,
                InvoiceStatus.WRITTEN_OFF);
        String home = settings.homeCurrency();
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        Map<Long, JobTally> byJob = new LinkedHashMap<>();
        Map<YearMonth, MonthTally> byMonth = new TreeMap<>();
        List<Invoice> issued = allTime ? invoices.findByStatusInWithClient(billed)
                : invoices.findByStatusInAndIssuedDateBetween(billed, from, to);
        for (Invoice invoice : issued) {
            String currency = invoice.getCurrency();
            var inHome = currency.equals(home) ? Optional.of(invoice.getAmount())
                    : fxRates.toHome(currency, invoice.getIssuedDate(), invoice.getAmount());
            if (inHome.isEmpty()) {
                continue;
            }
            total = total.add(inHome.get());
            count++;
            var project = invoice.getProject();
            byJob.computeIfAbsent(project.getId(),
                    id -> new JobTally(id, project.getName(), project.getClient().getName())).add(inHome.get());
            byMonth.computeIfAbsent(YearMonth.from(invoice.getIssuedDate()), MonthTally::new).add(inHome.get());
        }
        List<RevenueReportResponse.MonthRevenue> months = byMonth.values().stream().map(MonthTally::toResponse)
                .toList();
        List<RevenueReportResponse.JobRevenue> jobs = byJob.values().stream()
                .map(JobTally::toResponse)
                .sorted(Comparator.comparing(RevenueReportResponse.JobRevenue::amount).reversed()
                        .thenComparing(RevenueReportResponse.JobRevenue::projectId))
                .toList();
        return new RevenueReportResponse(allTime ? null : from, allTime ? null : to, home, count,
                total.setScale(2, RoundingMode.HALF_UP), jobs, months);
    }

    /** The running revenue of one job while the revenue report is built. */
    private static final class JobTally {
        private final Long projectId;
        private final String projectName;
        private final String clientName;
        private long invoices;
        private BigDecimal amount = BigDecimal.ZERO;

        JobTally(Long projectId, String projectName, String clientName) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.clientName = clientName;
        }

        void add(BigDecimal inHome) {
            amount = amount.add(inHome);
            invoices++;
        }

        RevenueReportResponse.JobRevenue toResponse() {
            return new RevenueReportResponse.JobRevenue(projectId, projectName, clientName, invoices,
                    amount.setScale(2, RoundingMode.HALF_UP));
        }
    }

    /** The running revenue of one calendar month while the revenue report is built. */
    private static final class MonthTally {
        private final YearMonth month;
        private long invoices;
        private BigDecimal amount = BigDecimal.ZERO;

        MonthTally(YearMonth month) {
            this.month = month;
        }

        void add(BigDecimal inHome) {
            amount = amount.add(inHome);
            invoices++;
        }

        RevenueReportResponse.MonthRevenue toResponse() {
            return new RevenueReportResponse.MonthRevenue(month.toString(), invoices,
                    amount.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
