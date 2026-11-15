package net.officefloor.hq.app.plan;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
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
import java.util.ArrayList;
import java.util.List;

/** A person's plan: a DRAFT claim of its amount for each plan date, up to its end. The plan dates
 *  are its start and then every so many days; or, for a monthly plan, its day of the month in every
 *  month from the start's month (a day beyond the month's length falling on its last day), leaving
 *  out those before the start. A date on or after the day the plan was paused and before the day it
 *  was resumed is skipped, not postponed; while it is paused, every date from the day it was. */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private long personId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    @Column(name = "every_days")
    private Integer every;

    @Column(name = "month_day")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    /** The sheet's today the plan was paused on; null while it runs. */
    @Column(name = "paused_on")
    private LocalDate pausedOn;

    /** The plan's past pauses, in the order they were resumed. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    private List<Pause> pauses = new ArrayList<>();

    /** The plan's amount changes, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_change", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    private List<Change> changes = new ArrayList<>();

    /** A change of the plan's amount: the sheet's today it was made on and the new amount. */
    @Embeddable
    public record Change(@Column(name = "on_date", nullable = false) LocalDate on,
            @Column(name = "amount", nullable = false, precision = 15, scale = 2) BigDecimal amount) {
    }

    /** A past pause: from the day the plan was paused (inclusive) to the day it was resumed (exclusive). */
    @Embeddable
    public record Pause(@Column(name = "from_date", nullable = false) LocalDate from,
            @Column(name = "to_date", nullable = false) LocalDate to) {
        /** Whether a plan date falls within it: on or after the from and before the to. */
        public boolean covers(LocalDate date) {
            return !date.isBefore(from) && date.isBefore(to);
        }
    }

    protected Plan() {
    }

    /** A plan of either an interval in days or a day of the month; the other is null. */
    public Plan(String sheetId, long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end) {
        this.sheetId = sheetId;
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

    public long getPersonId() {
        return personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getStart() {
        return start;
    }

    /** The interval in days; null for a monthly plan. */
    public Integer getEvery() {
        return every;
    }

    /** The day of the month of a monthly plan; null for a plan of an interval in days. */
    public Integer getDay() {
        return day;
    }

    public LocalDate getEnd() {
        return end;
    }

    public boolean isPaused() {
        return pausedOn != null;
    }

    /** The sheet's today the plan was paused on; null while it runs. */
    public LocalDate getPausedOn() {
        return pausedOn;
    }

    public List<Pause> getPauses() {
        return List.copyOf(pauses);
    }

    public List<Change> getChanges() {
        return List.copyOf(changes);
    }

    /** Change the plan's amount on a day: the claims it makes from then on are of the new amount. */
    public void changeAmount(LocalDate on, BigDecimal to) {
        amount = to;
        changes.add(new Change(on, to));
    }

    /** Pause the plan on a day: its dates from that day on are skipped until it is resumed. */
    public void pause(LocalDate on) {
        pausedOn = on;
    }

    /** Resume the plan on a day: its pause is kept as a past one, to that day. */
    public void resume(LocalDate on) {
        pauses.add(new Pause(pausedOn, on));
        pausedOn = null;
    }

    /** Whether the plan skips a date: one of its pauses covers it. */
    public boolean skips(LocalDate date) {
        return (pausedOn != null && !date.isBefore(pausedOn)) || pauses.stream().anyMatch(p -> p.covers(date));
    }

    /** The plan's dates up to its end and the date given that it does not skip, in order. */
    public List<LocalDate> datesUpTo(LocalDate until) {
        return allDatesUpTo(until).stream().filter(d -> !skips(d)).toList();
    }

    private List<LocalDate> allDatesUpTo(LocalDate until) {
        LocalDate last = end.isBefore(until) ? end : until;
        List<LocalDate> out = new ArrayList<>();
        if (day != null) {
            for (YearMonth m = YearMonth.from(start); !m.atDay(1).isAfter(last); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && !d.isAfter(last)) {
                    out.add(d);
                }
            }
            return out;
        }
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
            out.add(d);
        }
        return out;
    }
}
