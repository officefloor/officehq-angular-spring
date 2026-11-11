package net.officefloor.hq.app.plan;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A person's plan: an amount, a start date, every so many days or a day of the month, and an end date.
 * Its plan dates are the start, then every that many days, up to the end; or, monthly, that day in
 * every month from the start's month (a day beyond the month's length on its last day), dates before
 * the start left out. It keeps each date it has made a claim for, and each time it was paused: a plan
 * date on or after a pause's day and before its resume's day is skipped, not postponed. Its amount
 * can be changed; each change is kept, oldest first, and applies to the claims generated after it.
 * It may name one of its sheet's descendants: its claims are then made on that sheet instead.
 */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    @Column(name = "every_days")
    private Integer every;

    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @Column(name = "target_sheet_id")
    private String targetSheetId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "claim_date", nullable = false)
    private Set<LocalDate> made = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanPause> pauses = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_change", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "seq")
    private List<PlanChange> changes = new ArrayList<>();

    protected Plan() {
    }

    public Plan(String sheetId, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String targetSheetId) {
        this.sheetId = sheetId;
        this.targetSheetId = targetSheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.day = day;
        this.end = end;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /** The descendant sheet the plan's claims are made on, or null for its own sheet. */
    public String getTargetSheetId() {
        return targetSheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** Change the amount on the given day: the claims generated from then on take it. */
    public void changeAmount(LocalDate on, BigDecimal newAmount) {
        amount = newAmount;
        changes.add(new PlanChange(on, newAmount));
    }

    /** The changes of the plan's amount, oldest first. */
    public List<PlanChange> getChanges() {
        return changes;
    }

    public LocalDate getStart() {
        return start;
    }

    /** Every how many days, or null for a monthly plan. */
    public Integer getEvery() {
        return every;
    }

    /** The day of the month of a monthly plan, or null. */
    public Integer getDay() {
        return day;
    }

    /** Whether the plan is paused now: its last pause has not been resumed. */
    public boolean isPaused() {
        return !pauses.isEmpty() && pauses.get(pauses.size() - 1).isOpen();
    }

    /** Pause the plan from the given day; false when it is already paused. */
    public boolean pause(LocalDate on) {
        if (isPaused()) {
            return false;
        }
        pauses.add(new PlanPause(on));
        return true;
    }

    /** Resume the plan on the given day; false when it is not paused. */
    public boolean resume(LocalDate on) {
        if (!isPaused()) {
            return false;
        }
        pauses.get(pauses.size() - 1).resume(on);
        return true;
    }

    /** Whether a plan date is skipped by a pause. */
    public boolean skips(LocalDate date) {
        return pauses.stream().anyMatch(p -> p.skips(date));
    }

    /** The plan's dates from its start up to and including the last, in order. */
    public List<LocalDate> datesUpTo(LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
                dates.add(d);
            }
            return dates;
        }
        for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
            LocalDate d = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!d.isBefore(start) && !d.isAfter(last)) {
                dates.add(d);
            }
        }
        return dates;
    }

    /**
     * The plan's first dates after the given day, up to and including its end, those a pause skips
     * left out: at most count, in order.
     */
    public List<LocalDate> datesAfter(LocalDate after, int count) {
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            LocalDate d = start;
            if (!start.isAfter(after)) {
                long steps = ChronoUnit.DAYS.between(start, after) / every + 1;
                d = start.plusDays(steps * every);
            }
            for (; dates.size() < count && !d.isAfter(end); d = d.plusDays(every)) {
                if (!skips(d)) {
                    dates.add(d);
                }
            }
            return dates;
        }
        YearMonth month = YearMonth.from(after.isAfter(start) ? after : start);
        for (; dates.size() < count && !month.atDay(1).isAfter(end); month = month.plusMonths(1)) {
            LocalDate d = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!d.isBefore(start) && d.isAfter(after) && !d.isAfter(end) && !skips(d)) {
                dates.add(d);
            }
        }
        return dates;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** Whether the plan has made its claim for this date. */
    public boolean hasMade(LocalDate date) {
        return made.contains(date);
    }

    public void markMade(LocalDate date) {
        made.add(date);
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }
}
