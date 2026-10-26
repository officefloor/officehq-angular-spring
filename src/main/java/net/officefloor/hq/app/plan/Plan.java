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
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's plan: a claim of its amount on every plan date, up to its end. The plan dates are either the
 *  start then every N days, or, for a monthly plan, its day of the month in every month from the start's
 *  month (a day beyond the month's length falling on its last day), those before the start left out. */
@Entity
@Table(name = "person_plan")
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

    /** Every N days; null for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) of a monthly plan; null for any other. */
    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    /** The plan dates it has made a claim for. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "plan_date", nullable = false)
    private Set<LocalDate> generated = new HashSet<>();

    /** Its pauses, oldest first; one is still open while the plan is paused. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderBy("pausedOn")
    private List<PlanPause> pauses = new ArrayList<>();

    /** The changes of its amount, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_plan_change", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    private List<PlanChange> changes = new ArrayList<>();

    protected Plan() {
    }

    Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.day = day;
        this.end = end;
    }

    /** The plan dates, oldest first, on or before both its end and the date, those a pause skips left out. */
    List<LocalDate> datesUpTo(LocalDate upTo) {
        return scheduleUpTo(upTo).stream().filter((d) -> pauses.stream().noneMatch((p) -> p.skips(d))).toList();
    }

    /** The plan dates by its schedule alone, oldest first, on or before both its end and the date. */
    private List<LocalDate> scheduleUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) dates.add(d);
            return dates;
        }
        for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
            LocalDate d = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!d.isBefore(start) && !d.isAfter(last)) dates.add(d);
        }
        return dates;
    }

    /** Up to the first count plan dates by its schedule, oldest first, after the date and on or before its
     *  end; a pause does not hide them (it only skips them when claims are generated). */
    public List<LocalDate> datesAfter(LocalDate after, int count) {
        return scheduleUpTo(end).stream().filter((d) -> d.isAfter(after)).limit(count).toList();
    }

    boolean hasGenerated(LocalDate date) {
        return generated.contains(date);
    }

    void markGenerated(LocalDate date) {
        generated.add(date);
    }

    /** Whether the plan is paused now. */
    public boolean isPaused() {
        return pauses.stream().anyMatch(PlanPause::isOpen);
    }

    /** Pause the plan on the date; the caller has checked it is running. */
    void pause(LocalDate on) {
        pauses.add(new PlanPause(on));
    }

    /** Resume the plan on the date; the caller has checked it is paused. */
    void resume(LocalDate on) {
        pauses.stream().filter(PlanPause::isOpen).forEach((p) -> p.resume(on));
    }

    /** Change the amount on the date, for the claims generated from then on. */
    void changeAmount(LocalDate on, BigDecimal newAmount) {
        amount = newAmount;
        changes.add(new PlanChange(on, newAmount));
    }

    /** The lines of its amount changes, oldest first: the day, then amount=the new amount. */
    public List<String> changeLines() {
        return changes.stream().map(PlanChange::line).toList();
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getStart() {
        return start;
    }

    public Integer getEvery() {
        return every;
    }

    public Integer getDay() {
        return day;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** How many claims the plan has made so far. */
    public int getGeneratedCount() {
        return generated.size();
    }
}
