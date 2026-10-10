package net.officefloor.hq.app.recurring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** An invoice on a project that repeats on a schedule for a fixed amount, next falling on a given day. */
@Entity
@Table(name = "recurring_invoice")
public class RecurringInvoice {

    /** The billing period a schedule's first invoice is pro-rated over when none is given. */
    public static final int DEFAULT_PERIOD_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringFrequency frequency;

    @Column(name = "next_date", nullable = false)
    private LocalDate nextDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecurringStatus status = RecurringStatus.ACTIVE;

    @Column(name = "period_days", nullable = false)
    private int periodDays = DEFAULT_PERIOD_DAYS;

    /** Whether the next invoice is the first, to be pro-rated by the days left in its period. */
    @Column(name = "prorate_first", nullable = false)
    private boolean prorateFirst;

    protected RecurringInvoice() {
    }

    public RecurringInvoice(Long projectId, BigDecimal amount, RecurringFrequency frequency, LocalDate nextDate) {
        this.projectId = projectId;
        this.amount = amount.setScale(2);
        this.frequency = frequency;
        this.nextDate = nextDate;
    }

    public RecurringInvoice(Long projectId, BigDecimal amount, RecurringFrequency frequency, LocalDate nextDate,
            int periodDays, boolean prorateFirst) {
        this(projectId, amount, frequency, nextDate);
        this.periodDays = periodDays;
        this.prorateFirst = prorateFirst;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public LocalDate getNextDate() {
        return nextDate;
    }

    public RecurringStatus getStatus() {
        return status;
    }

    public int getPeriodDays() {
        return periodDays;
    }

    public boolean isProrateFirst() {
        return prorateFirst;
    }

    /**
     * The days of the billing period left from the next invoice date, counting that day: the period starts on
     * the first of the month and runs for {@code periodDays} days. At least one day is always billed.
     */
    public int daysLeftInPeriod() {
        return Math.max(1, Math.min(periodDays, periodDays - (nextDate.getDayOfMonth() - 1)));
    }

    /**
     * The amount the next invoice is raised for: the first of a pro-rated schedule bills only the share of the
     * amount for the days left in its period, rounded to the cent; every other invoice bills the full amount.
     */
    public BigDecimal nextAmount() {
        if (!prorateFirst) {
            return amount;
        }
        return amount.multiply(BigDecimal.valueOf(daysLeftInPeriod()))
                .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
    }

    public boolean isPaused() {
        return status == RecurringStatus.PAUSED;
    }

    /** Stops the schedule generating invoices until it is resumed. */
    public void pause() {
        this.status = RecurringStatus.PAUSED;
    }

    /** Lets a paused schedule generate invoices again. */
    public void resume() {
        this.status = RecurringStatus.ACTIVE;
    }

    /** Moves the schedule on to the date the invoice after this one falls on. */
    public void advance() {
        this.prorateFirst = false;
        this.nextDate = switch (frequency) {
            case MONTHLY -> nextDate.plusMonths(1);
        };
    }
}
