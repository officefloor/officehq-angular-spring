package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** A revision of a period in its sheet's history: its days, whether it was closed or soft-closed,
 *  and the due-totals fixed then, after a change, recorded on the sheet's today then. */
@Entity
@Table(name = "period_revision")
public class PeriodRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    @Column(name = "claim_cap")
    private Integer claimCap;

    /** Each person's fixed due-total, "person-id:total", separated by ";"; blank while open. */
    @Column(name = "person_totals", nullable = false)
    private String personTotals;

    /** Each person's fixed carried figure, "person-id:carried", separated by ";"; blank while open. */
    @Column(name = "person_carried", nullable = false)
    private String personCarried;

    protected PeriodRevision() {
    }

    /** A revision of the period as it is now, recorded on the day. */
    public PeriodRevision(Period period, LocalDate recordedOn) {
        this.sheetId = period.getSheetId();
        this.periodId = period.getId();
        this.recordedOn = recordedOn;
        this.name = period.getName();
        this.fromDate = period.getFromDate();
        this.toDate = period.getToDate();
        this.closed = period.isClosed();
        this.softClosed = period.isSoftClosed();
        this.closedTotal = period.getClosedTotal();
        this.personTotals = totals(period.getPersonTotals());
        this.personCarried = totals(period.getPersonCarried());
        this.claimCap = period.getClaimCap();
    }

    private static String totals(Map<Long, BigDecimal> totals) {
        return new TreeMap<>(totals).entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().setScale(2).toPlainString())
                .collect(Collectors.joining(";"));
    }

    /** Whether the period is now as this revision has it. */
    public boolean matches(Period period) {
        return Objects.equals(name, period.getName()) && Objects.equals(fromDate, period.getFromDate())
                && Objects.equals(toDate, period.getToDate()) && closed == period.isClosed()
                && softClosed == period.isSoftClosed()
                && (closedTotal == null ? period.getClosedTotal() == null
                        : period.getClosedTotal() != null && closedTotal.compareTo(period.getClosedTotal()) == 0)
                && personTotals.equals(totals(period.getPersonTotals()))
                && personCarried.equals(totals(period.getPersonCarried()))
                && Objects.equals(claimCap, period.getClaimCap());
    }

    /** The period as this revision has it, detached: for showing only, never saved. */
    public Period toPeriod() {
        return Period.revised(sheetId, periodId, name, fromDate, toDate, closed, softClosed, closedTotal,
                parse(personTotals), parse(personCarried), claimCap);
    }

    private static Map<Long, BigDecimal> parse(String text) {
        Map<Long, BigDecimal> figures = new TreeMap<>();
        Arrays.stream(text.split(";")).filter(t -> !t.isBlank()).forEach(t -> {
            int at = t.indexOf(':');
            figures.put(Long.valueOf(t.substring(0, at)), new BigDecimal(t.substring(at + 1)));
        });
        return figures;
    }

    public Long getPeriodId() {
        return periodId;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }
}
