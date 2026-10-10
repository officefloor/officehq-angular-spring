package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
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
import net.officefloor.hq.app.payment.Payment;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import net.officefloor.hq.app.settings.RecognitionBasis;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.task.TaskRepository;
import net.officefloor.hq.app.taxadjustment.TaxAdjustment;
import net.officefloor.hq.app.taxadjustment.TaxAdjustmentRepository;
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
    private final TaxAdjustmentRepository taxAdjustments;
    private final Clock clock;

    /** How many of the biggest debtors the dashboard lists. */
    static final int TOP_CLIENTS = 5;

    public DashboardService(ClientRepository clients, ProjectRepository projects, InvoiceRepository invoices,
            ClientService clientService, PaymentRepository payments, CreditNoteRepository creditNotes,
            SettingsService settings, FxRateService fxRates, InstalmentRepository instalments, TaskRepository tasks,
            TaxAdjustmentRepository taxAdjustments, Clock clock) {
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
        this.taxAdjustments = taxAdjustments;
        this.clock = clock;
    }

    /**
     * Counts of clients and projects, and the total still owed in each currency (what is left to pay
     * on sent invoices that are not yet fully paid; a currency with written-off invoices is listed even when nothing is owed), plus how many of those sent invoices (not disputed) are past their due date and
     * what is overdue on them (in total and split by how many days overdue each invoice is), what is outstanding as one grand total in the home currency (see {@link #inHome}; disputed and written-off invoices left out), and the top clients ranked by what they owe converted into the home currency (a client none of whose debt can be converted is left out), and how many tasks not yet done are past their due date, and how many clients were taken on this month, and what was billed this month and this year in the home currency (disputed and written-off invoices left out, as for what is outstanding), and what is left to pay on disputed invoices in the home currency.
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
        BigDecimal outstandingHome = owedInHome.entrySet().stream().filter(e -> countsInKpis(e.getKey()))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        // What is tied up in disputed invoices: what is left to pay on them, which the outstanding total leaves out.
        BigDecimal disputedAmount = owedInHome.entrySet().stream().filter(e -> e.getKey().isDisputed())
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
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
        BigDecimal billingsYearToDate = billings(today.withDayOfYear(1), today, home);
        BigDecimal billingTarget = settings.billingTarget();
        Long billingTargetProgress = billingTarget == null ? null
                : billingsYearToDate.multiply(BigDecimal.valueOf(100)).divide(billingTarget, 0, RoundingMode.HALF_UP)
                        .longValue();
        String base = settings.dashboardBaseCurrency();
        String shownIn = shownIn(home, today);
        UnaryOperator<BigDecimal> shown = shown(home, shownIn, today);
        Map<Long, BigDecimal> overdueByClient = new HashMap<>();
        Map<Long, String> overdueClientNames = new HashMap<>();
        overdueInHome.forEach((invoice, amount) -> {
            var client = invoice.getProject().getClient();
            overdueByClient.merge(client.getId(), amount, BigDecimal::add);
            overdueClientNames.put(client.getId(), client.getName());
        });
        List<DashboardResponse.TopOverdueClient> topOverdue = overdueByClient.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed()
                        .thenComparing(e -> overdueClientNames.get(e.getKey())))
                .limit(TOP_CLIENTS)
                .map(e -> new DashboardResponse.TopOverdueClient(e.getKey(), overdueClientNames.get(e.getKey()),
                        shown.apply(e.getValue().setScale(2, RoundingMode.HALF_UP))))
                .toList();
        top = top.stream().map(c -> new DashboardResponse.TopClient(c.id(), c.name(), c.currency(), c.outstanding(),
                shown.apply(c.outstandingHome()))).toList();
        overdueBuckets = new DashboardResponse.OverdueBuckets(shown.apply(overdueBuckets.days0To30()),
                shown.apply(overdueBuckets.days31To60()), shown.apply(overdueBuckets.days60Plus()));
        // Totals built from converted parts add up from those parts, so they never disagree with them by a rounding.
        BigDecimal overdueShown = shownIn.equals(home) ? overdueAmount
                : overdueBuckets.days0To30().add(overdueBuckets.days31To60()).add(overdueBuckets.days60Plus());
        BigDecimal billingsYearToDateShown = shown.apply(billingsYearToDate);
        BigDecimal billingTargetShown = shown.apply(billingTarget);
        BigDecimal billingTargetVarianceShown = billingTarget == null ? null
                : billingsYearToDateShown.subtract(billingTargetShown);
        return new DashboardResponse(clients.count(), projects.count(), outstanding, shown.apply(outstandingHome), overdue,
                shownIn, overdueShown, overdueBuckets, top, tasks.countByDoneFalse(),
                tasks.countByDoneFalseAndDueDateBefore(today), averageDaysToPay(),
                clients.countByCreatedDateGreaterThanEqual(today.withDayOfMonth(1)),
                shown.apply(billings(today.withDayOfMonth(1), today, home)), collectionRate(home),
                billingsYearToDateShown, shown.apply(collected(today.withDayOfYear(1), today, home)),
                shown.apply(collected(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, home)),
                taskCompletionRate(), billingTargetShown, billingTargetProgress,
                billingTargetVarianceShown, base, topOverdue, shown.apply(disputedAmount));
    }

    /**
     * The currency the dashboard's totals are shown in: the chosen base currency when it can be converted into from
     * the home currency at today's rate, otherwise (no rate for it yet) the home currency itself.
     */
    private String shownIn(String home, LocalDate today) {
        String base = settings.dashboardBaseCurrency();
        return fxRates.convert(home, base, today, BigDecimal.ONE).isPresent() ? base : home;
    }

    /** Converts a home-currency total into the shown currency at today's rate, as every dashboard total is. */
    private UnaryOperator<BigDecimal> shown(String home, String shownIn, LocalDate today) {
        return amount -> amount == null ? null : fxRates.convert(home, shownIn, today, amount).orElseThrow();
    }

    /** The share of all tasks that are done, as a whole percentage. Null when there are no tasks. */
    private Long taskCompletionRate() {
        long total = tasks.count();
        if (total == 0) {
            return null;
        }
        long done = total - tasks.countByDoneFalse();
        return BigDecimal.valueOf(done).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * What was collected on or between the given dates, in the home currency: the payments received in the range,
     * each converted at its invoice's issue date's rate (a payment that cannot be converted is left out).
     */
    private BigDecimal collected(LocalDate from, LocalDate to, String home) {
        List<Payment> received = payments.findByDateBetween(from, to);
        Map<Long, Invoice> paidInvoices = invoices.findAllById(received.stream()
                .map(Payment::getInvoiceId).distinct().toList()).stream()
                .collect(Collectors.toMap(Invoice::getId, i -> i));
        return received.stream().map(p -> {
            Invoice invoice = paidInvoices.get(p.getInvoiceId());
            if (invoice == null) {
                return Optional.<BigDecimal>empty();
            }
            return invoice.getCurrency().equals(home) ? Optional.of(p.getAmount())
                    : fxRates.toHome(invoice.getCurrency(), invoice.getIssuedDate(), p.getAmount());
        }).flatMap(Optional::stream).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The share of everything billed that has been collected, as a whole percentage: the payments received against
     * every invoice that was sent (drafts and cancelled invoices are left out) over what those invoices were billed
     * for, both in the home currency at each invoice's issue date's rate (an invoice that cannot be converted is left
     * out). Null when nothing has been billed.
     */
    private Long collectionRate(String home) {
        List<Invoice> billed = invoices.findByStatusInWithClient(List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL,
                InvoiceStatus.PAID, InvoiceStatus.WRITTEN_OFF));
        if (billed.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> paid = payments.sumAmountByInvoiceIds(billed.stream().map(Invoice::getId).toList())
                .stream().collect(Collectors.toMap(PaymentRepository.InvoicePaidTotal::getInvoiceId,
                        PaymentRepository.InvoicePaidTotal::getPaid));
        BigDecimal billedTotal = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        for (Invoice invoice : billed) {
            String currency = invoice.getCurrency();
            BigDecimal amount = invoice.getAmount();
            BigDecimal received = paid.getOrDefault(invoice.getId(), BigDecimal.ZERO);
            if (!currency.equals(home)) {
                var amountInHome = fxRates.toHome(currency, invoice.getIssuedDate(), amount);
                var receivedInHome = fxRates.toHome(currency, invoice.getIssuedDate(), received);
                if (amountInHome.isEmpty() || receivedInHome.isEmpty()) {
                    continue;
                }
                amount = amountInHome.get();
                received = receivedInHome.get();
            }
            billedTotal = billedTotal.add(amount);
            collected = collected.add(received);
        }
        if (billedTotal.signum() <= 0) {
            return null;
        }
        return collected.multiply(BigDecimal.valueOf(100)).divide(billedTotal, 0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * Whether an invoice counts towards the dashboard's money figures (what is outstanding and what was billed): the
     * same rule for each so they agree, leaving out disputed and written-off invoices.
     */
    private static boolean countsInKpis(Invoice invoice) {
        return !invoice.isDisputed() && invoice.getStatus() != InvoiceStatus.WRITTEN_OFF;
    }

    /**
     * What was billed on or between the given dates, in the home currency: the amounts of the invoices issued in the
     * range that were sent (drafts and cancelled invoices are left out, and disputed and written-off ones too, see
     * {@link #countsInKpis}), each converted at its issue date's rate (an invoice that cannot be converted is left out).
     */
    private BigDecimal billings(LocalDate from, LocalDate to, String home) {
        List<InvoiceStatus> billed = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID);
        return invoices.findByStatusInAndIssuedDateBetween(billed, from, to).stream()
                .filter(DashboardService::countsInKpis)
                .map(i -> i.getCurrency().equals(home) ? Optional.of(i.getAmount())
                        : fxRates.toHome(i.getCurrency(), i.getIssuedDate(), i.getAmount()))
                .flatMap(Optional::stream).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The average number of days clients take to pay, rounded to the nearest whole day: for each paid invoice with an
     * issue date, the days from its issue date to the date of its last payment. Null when no invoice qualifies.
     */
    private Long averageDaysToPay() {
        Map<Long, LocalDate> issued = new HashMap<>();
        invoices.findAllWithProjectByStatus(InvoiceStatus.PAID).stream().filter(i -> i.getIssuedDate() != null)
                .forEach(i -> issued.put(i.getId(), i.getIssuedDate()));
        if (issued.isEmpty()) {
            return null;
        }
        Map<Long, LocalDate> settled = new HashMap<>();
        payments.findByInvoiceIdIn(issued.keySet())
                .forEach(p -> settled.merge(p.getInvoiceId(), p.getDate(), (a, b) -> a.isAfter(b) ? a : b));
        if (settled.isEmpty()) {
            return null;
        }
        double average = settled.entrySet().stream()
                .mapToLong(e -> Math.max(0, ChronoUnit.DAYS.between(issued.get(e.getKey()), e.getValue())))
                .average().orElse(0);
        return Math.round(average);
    }

    /**
     * How old the debt across all clients is as at today: what is left to pay on each sent invoice not yet fully
     * paid, converted into the home currency (see {@link #inHome}), split by how many days past its due date it is.
     * An invoice not yet due, or without a due date, is current.
     */
    @Transactional(readOnly = true)
    public AgingReportResponse agingReport() {
        LocalDate today = LocalDate.now(clock);
        String home = settings.homeCurrency();
        Map<Invoice, BigDecimal> owedInHome = inHome(
                invoices.findByStatusInWithClient(List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL)), home, today, false);
        DashboardResponse.OverdueBuckets aged = buckets(owedInHome, today);
        List<AgingReportResponse.Line> lines = owedInHome.entrySet().stream().map(e -> {
            Invoice invoice = e.getKey();
            long days = daysOverdue(invoice, today);
            AgingReportResponse.Bucket bucket = days > 60 ? AgingReportResponse.Bucket.DAYS_60_PLUS
                    : days > 30 ? AgingReportResponse.Bucket.DAYS_30_60 : AgingReportResponse.Bucket.CURRENT;
            return new AgingReportResponse.Line(invoice.getId(), invoice.getProject().getId(),
                    invoice.getProject().getClient().getId(), invoice.getProject().getClient().getName(),
                    invoice.getDueDate(), Math.max(0, days), bucket, e.getValue().setScale(2, RoundingMode.HALF_UP));
        }).sorted(Comparator.comparingLong(AgingReportResponse.Line::daysOverdue).reversed()
                .thenComparing(AgingReportResponse.Line::invoiceId)).toList();
        return new AgingReportResponse(today, home, aged.days0To30(), aged.days31To60(), aged.days60Plus(),
                sum(owedInHome), lines);
    }

    /** How many months the overdue trend covers, the current month included. */
    static final int OVERDUE_TREND_MONTHS = 6;

    /**
     * What was overdue at the close of each of the recent months, in the home currency, ending with the current
     * month. The current month's figure is what is overdue today, exactly as on the dashboard. An earlier month's is
     * rebuilt from the record: what was left to pay at the month's close (less the payments made by then, and the
     * credit notes and any write-off or rebate) on each sent invoice not disputed nor cancelled whose due date had
     * passed, plus the late fee built up by then. An invoice marked paid by hand, without payments to date it by, is
     * left out of earlier months. A foreign invoice converts at its issue date's rate, as elsewhere.
     */
    @Transactional(readOnly = true)
    public OverdueTrendResponse overdueTrend() {
        LocalDate today = LocalDate.now(clock);
        String home = settings.homeCurrency();
        List<InvoiceStatus> owing = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL);
        List<Invoice> sent = invoices.findByStatusInWithClient(
                List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID, InvoiceStatus.WRITTEN_OFF))
                .stream().filter(i -> !i.isDisputed() && i.getDueDate() != null).toList();
        List<Long> ids = sent.stream().map(Invoice::getId).toList();
        Map<Long, List<Payment>> paymentsByInvoice = ids.isEmpty() ? Map.of()
                : payments.findByInvoiceIdIn(ids).stream().collect(Collectors.groupingBy(Payment::getInvoiceId));
        Map<Long, BigDecimal> credited = ids.isEmpty() ? Map.of()
                : creditNotes.sumAmountByInvoiceIds(ids).stream()
                        .collect(Collectors.toMap(CreditNoteRepository.InvoiceCreditedTotal::getInvoiceId,
                                CreditNoteRepository.InvoiceCreditedTotal::getCredited));

        YearMonth current = YearMonth.from(today);
        List<OverdueTrendResponse.Month> months = new ArrayList<>();
        BigDecimal previous = null;
        for (int back = OVERDUE_TREND_MONTHS - 1; back >= 0; back--) {
            YearMonth month = current.minusMonths(back);
            LocalDate asOf = back == 0 ? today : month.atEndOfMonth();
            BigDecimal amount = back == 0
                    ? sum(inHome(invoices.findByStatusInAndDueDateBefore(owing, today), home, today, true))
                    : overdueAt(sent, paymentsByInvoice, credited, home, asOf);
            months.add(new OverdueTrendResponse.Month(month.toString(), asOf, amount,
                    previous == null ? null : amount.subtract(previous)));
            previous = amount;
        }
        return new OverdueTrendResponse(today, home, months);
    }

    /** What was overdue on the given invoices at the close of the given day, in the home currency. */
    private BigDecimal overdueAt(List<Invoice> sent, Map<Long, List<Payment>> paymentsByInvoice,
            Map<Long, BigDecimal> credited, String home, LocalDate asOf) {
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : sent) {
            if (!invoice.getDueDate().isBefore(asOf)
                    || (invoice.getIssuedDate() != null && invoice.getIssuedDate().isAfter(asOf))) {
                continue;
            }
            List<Payment> made = paymentsByInvoice.getOrDefault(invoice.getId(), List.of());
            if (invoice.getStatus() == InvoiceStatus.PAID && made.isEmpty()) {
                continue;
            }
            BigDecimal paid = made.stream().filter(p -> !p.getDate().isAfter(asOf)).map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Worked out as though still owing, so a write-off is taken off rather than clearing the invoice.
            BigDecimal due = invoice.getAmount().subtract(paid)
                    .subtract(credited.getOrDefault(invoice.getId(), BigDecimal.ZERO))
                    .subtract(invoice.getWriteOffAmount()).subtract(invoice.getRebateTaken());
            if (due.signum() <= 0) {
                continue;
            }
            due = due.add(invoice.lateFee(InvoiceStatus.SENT, asOf));
            String currency = invoice.getProject().getClient().getCurrency();
            BigDecimal inHome = currency.equals(home) ? due
                    : fxRates.toHome(currency, invoice.getIssuedDate(), due).orElse(null);
            if (inHome != null) {
                total = total.add(inHome);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** How many days past its due date an invoice is as at today; one without a due date is not overdue. */
    private static long daysOverdue(Invoice invoice, LocalDate today) {
        LocalDate dueDate = invoice.getDueDate();
        return dueDate == null ? 0 : ChronoUnit.DAYS.between(dueDate, today);
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
            long days = daysOverdue(e.getKey(), today);
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
     * sent and is not yet fully paid, earliest due first, with the totals in the dashboard's shown currency (see
     * {@link #shownIn}). A foreign instalment converts at the exchange rate from its invoice's issue date; one whose
     * currency has no rate by then is still listed but left out of the total.
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
        LocalDate today = LocalDate.now(clock);
        // The totals are shown in the dashboard's chosen base currency, as the dashboard's other totals are.
        String shownIn = shownIn(home, today);
        UnaryOperator<BigDecimal> shown = shown(home, shownIn, today);
        ForecastResponse.Window next30Days = dueWithin(List.copyOf(open.values()), today,
                today.plusDays(FORECAST_DAYS), home);
        return new ForecastResponse(shownIn, entries, shown.apply(total.setScale(2, RoundingMode.HALF_UP)),
                new ForecastResponse.Window(next30Days.from(), next30Days.to(), shown.apply(next30Days.total())));
    }

    /** How many days ahead the forecast of what can be collected looks. */
    static final int FORECAST_DAYS = 30;

    /**
     * What can be expected to be collected on or between the given dates, in the home currency, from the given open
     * invoices (disputed ones are left out): an invoice paid by instalments brings in its unpaid instalments due in the
     * window; any other brings in what is left to pay on it when its due date is in the window. Anything already
     * overdue is not counted. A foreign amount converts at its invoice's issue date's rate; one that cannot be
     * converted is left out.
     */
    private ForecastResponse.Window dueWithin(List<Invoice> open, LocalDate from, LocalDate to, String home) {
        List<Invoice> undisputed = open.stream().filter(i -> !i.isDisputed()).toList();
        Map<Long, List<Instalment>> scheduled = undisputed.isEmpty() ? Map.of()
                : instalments.findByInvoiceIdIn(undisputed.stream().map(Invoice::getId).toList()).stream()
                        .collect(Collectors.groupingBy(Instalment::getInvoiceId));
        BigDecimal total = BigDecimal.ZERO;
        List<Invoice> unscheduled = new ArrayList<>();
        for (Invoice invoice : undisputed) {
            List<Instalment> plan = scheduled.get(invoice.getId());
            if (plan == null) {
                if (invoice.getDueDate() != null && !invoice.getDueDate().isBefore(from)
                        && !invoice.getDueDate().isAfter(to)) {
                    unscheduled.add(invoice);
                }
                continue;
            }
            BigDecimal due = plan.stream().filter(i -> !i.isPaid())
                    .filter(i -> !i.getDueDate().isBefore(from) && !i.getDueDate().isAfter(to))
                    .map(Instalment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String currency = invoice.getCurrency();
            total = total.add(currency.equals(home) ? due
                    : fxRates.toHome(currency, invoice.getIssuedDate(), due).orElse(BigDecimal.ZERO));
        }
        total = total.add(sum(inHome(unscheduled, home, from, false)));
        return new ForecastResponse.Window(from, to, total.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The tax charged over a period, in the home currency: the sales tax and levy on every invoice issued on or
     * between the given dates that was actually charged (sent, whether paid or not, or later written off; drafts
     * and cancelled invoices are left out). A foreign invoice converts at the exchange rate from its issue date;
     * one whose currency has no rate by then cannot be converted and is left out. The manual tax adjustments dated
     * within the period are netted into the total; one recorded in another (earlier) home currency converts at the
     * rate from its date, and is left out when there is none.
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
        BigDecimal adjustments = BigDecimal.ZERO.setScale(2);
        for (TaxAdjustment adjustment : taxAdjustments.findByAdjustmentDateBetween(from, to)) {
            if (adjustment.getCurrency().equals(home)) {
                adjustments = adjustments.add(adjustment.getAmount());
            } else {
                adjustments = adjustments.add(fxRates
                        .toHome(adjustment.getCurrency(), adjustment.getAdjustmentDate(), adjustment.getAmount())
                        .orElse(BigDecimal.ZERO));
            }
        }
        adjustments = adjustments.setScale(2, RoundingMode.HALF_UP);
        return new TaxSummaryResponse(from, to, home, count, tax, levy, adjustments, tax.add(levy).add(adjustments));
    }

    /**
     * The sales tax charged on invoices issued on or between the given dates that were sent (drafts and cancelled
     * invoices are left out), broken down by the invoice's tax rate: the taxable base and the tax at each rate, each
     * converted into the home currency at its issue date's rate (an invoice that cannot be converted is left out).
     * Invoices with nothing taxable on them are left out.
     */
    @Transactional(readOnly = true)
    public TaxReportResponse taxReport(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date");
        }
        List<InvoiceStatus> charged = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID,
                InvoiceStatus.WRITTEN_OFF);
        String home = settings.homeCurrency();
        Map<BigDecimal, RateTally> byRate = new TreeMap<>();
        for (Invoice invoice : invoices.findByStatusInAndIssuedDateBetween(charged, from, to)) {
            BigDecimal base = invoice.getTaxableBase();
            if (base.signum() == 0) {
                continue;
            }
            String currency = invoice.getCurrency();
            BigDecimal tax = invoice.getTax();
            if (!currency.equals(home)) {
                var inHomeBase = fxRates.toHome(currency, invoice.getIssuedDate(), base);
                var inHomeTax = fxRates.toHome(currency, invoice.getIssuedDate(), tax);
                if (inHomeBase.isEmpty() || inHomeTax.isEmpty()) {
                    continue;
                }
                base = inHomeBase.get();
                tax = inHomeTax.get();
            }
            byRate.computeIfAbsent(invoice.getTaxPct().stripTrailingZeros(), r -> new RateTally()).add(base, tax);
        }
        List<TaxReportResponse.RateTax> rates = new ArrayList<>();
        BigDecimal totalBase = BigDecimal.ZERO.setScale(2);
        BigDecimal totalTax = BigDecimal.ZERO.setScale(2);
        long count = 0;
        for (var entry : byRate.entrySet()) {
            RateTally tally = entry.getValue();
            BigDecimal base = tally.base.setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = tally.tax.setScale(2, RoundingMode.HALF_UP);
            rates.add(new TaxReportResponse.RateTax(entry.getKey().setScale(Math.max(0, entry.getKey().scale())),
                    tally.invoices, base, tax));
            totalBase = totalBase.add(base);
            totalTax = totalTax.add(tax);
            count += tally.invoices;
        }
        return new TaxReportResponse(from, to, home, count, totalBase, totalTax, rates);
    }

    /** The taxable base and tax added up at one tax rate. */
    private static final class RateTally {
        private BigDecimal base = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;
        private long invoices;

        void add(BigDecimal base, BigDecimal tax) {
            this.base = this.base.add(base);
            this.tax = this.tax.add(tax);
            invoices++;
        }
    }

    /**
     * The revenue billed on or between the given dates (over all time when either date is missing): the amounts of
     * the invoices issued in the range that were sent (drafts and cancelled invoices are left out) or, when the
     * settings count revenue once paid, only those fully paid, each converted
     * into the home currency at its issue date's rate (an invoice that cannot be converted is left out). The revenue
     * is also broken down by job (project), highest-earning first (non-billable jobs are left out of the breakdown), and by the month each invoice was issued in, earliest first, to show the trend.
     */
    @Transactional(readOnly = true)
    public RevenueReportResponse revenueReport(LocalDate from, LocalDate to) {
        boolean allTime = from == null || to == null;
        if (!allTime && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date");
        }
        List<InvoiceStatus> billed = RecognitionBasis.PAID.equals(settings.revenueRecognitionBasis())
                ? List.of(InvoiceStatus.PAID)
                : List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID, InvoiceStatus.WRITTEN_OFF);
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
            if (project.isBillable()) {
                byJob.computeIfAbsent(project.getId(),
                        id -> new JobTally(id, project.getName(), project.getClient().getName())).add(inHome.get());
            }
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

    /**
     * The revenue report as a small CSV file: a header row, one row of job, client, invoices and revenue per job (in
     * the report's order), then a total row. Amounts are in the home currency.
     */
    @Transactional(readOnly = true)
    public String revenueReportCsv(LocalDate from, LocalDate to) {
        RevenueReportResponse report = revenueReport(from, to);
        StringBuilder text = new StringBuilder("job,client,invoices,revenue\r\n");
        for (RevenueReportResponse.JobRevenue job : report.jobs()) {
            text.append(csv(neutralise(job.projectName()))).append(',').append(csv(neutralise(job.clientName())))
                    .append(',').append(job.invoices()).append(',').append(job.amount().toPlainString())
                    .append("\r\n");
        }
        text.append("Total,,").append(report.invoices()).append(',').append(report.total().toPlainString())
                .append("\r\n");
        return text.toString();
    }

    /**
     * The aging report as a small CSV file: a header row, one row of invoice, client, due date, days overdue, bucket
     * and outstanding amount per invoice (in the report's order), then a row per bucket and a total row. Amounts are
     * in the home currency.
     */
    @Transactional(readOnly = true)
    public String agingReportCsv() {
        AgingReportResponse report = agingReport();
        StringBuilder text = new StringBuilder("invoice,client,due date,days overdue,bucket,outstanding\r\n");
        for (AgingReportResponse.Line line : report.invoices()) {
            text.append(line.invoiceId()).append(',').append(csv(neutralise(line.clientName()))).append(',')
                    .append(line.dueDate() == null ? "" : line.dueDate().toString()).append(',')
                    .append(line.daysOverdue()).append(',').append(line.bucket()).append(',')
                    .append(line.amount().toPlainString()).append("\r\n");
        }
        text.append("Current,,,,,").append(report.current().toPlainString()).append("\r\n");
        text.append("31 to 60 days,,,,,").append(report.days30To60().toPlainString()).append("\r\n");
        text.append("More than 60 days,,,,,").append(report.days60Plus().toPlainString()).append("\r\n");
        text.append("Total,,,,,").append(report.total().toPlainString()).append("\r\n");
        return text.toString();
    }

    /** Stops a value a spreadsheet would read as a formula from being run when the file is opened. */
    private static String neutralise(String value) {
        if (value != null && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    /** One CSV field, quoted when it holds a comma, quote or line break; a missing value is left empty. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.matches("(?s).*[\",\r\n].*")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * The revenue billed in two periods side by side, each worked out as the revenue report over its dates, with the
     * change from the first period to the second.
     */
    @Transactional(readOnly = true)
    public RevenueComparisonResponse revenueComparison(LocalDate aFrom, LocalDate aTo, LocalDate bFrom,
            LocalDate bTo) {
        RevenueReportResponse a = revenueReport(aFrom, aTo);
        RevenueReportResponse b = revenueReport(bFrom, bTo);
        BigDecimal change = b.total().subtract(a.total());
        Long changePercent = a.total().signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(a.total(), 0, RoundingMode.HALF_UP).longValue();
        return new RevenueComparisonResponse(a.homeCurrency(),
                new RevenueComparisonResponse.Period(aFrom, aTo, a.invoices(), a.total()),
                new RevenueComparisonResponse.Period(bFrom, bTo, b.invoices(), b.total()), change, changePercent);
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
