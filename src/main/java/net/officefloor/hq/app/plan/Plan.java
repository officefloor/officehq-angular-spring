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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's plan: a DRAFT claim of its amount on every plan date up to its end — the start then every so
 *  many days, or monthly on a day of the month — and the plan dates it has made a claim for so far. */
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

    /** Every so many days; null for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) of a monthly plan; null for one run every so many days. */
    @Column(name = "month_day")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_generated", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "on_date")
    private Set<LocalDate> generated = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanPause> pauses = new ArrayList<>();

    protected Plan() {
    }

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

    /** Whether the plan is paused and not yet resumed. */
    public boolean isPaused() {
        return pauses.stream().anyMatch(p -> p.getResumedOn() == null);
    }

    /** Pause the plan from the given day. */
    public void pause(LocalDate on) {
        pauses.add(new PlanPause(on));
    }

    /** Resume the paused plan from the given day. */
    public void resume(LocalDate on) {
        pauses.stream().filter(p -> p.getResumedOn() == null).forEach(p -> p.resume(on));
    }

    /** The plan dates up to its end and the given day that it has made no claim for yet and that no pause
     *  skips, earliest first. */
    public List<LocalDate> datesDue(LocalDate today) {
        LocalDate last = end.isBefore(today) ? end : today;
        return dates(start, last, Integer.MAX_VALUE).stream().filter(d -> !generated.contains(d)).toList();
    }

    /** The first so many plan dates after the given day and on or before its end that no pause skips,
     *  earliest first, whether or not it has made a claim for them. A plan still paused has none. */
    public List<LocalDate> nextDates(LocalDate today, int count) {
        if (isPaused()) {
            return List.of();
        }
        return dates(today.plusDays(1), end, count);
    }

    private boolean skipped(LocalDate date) {
        return pauses.stream().anyMatch(p -> p.skips(date));
    }

    /** The plan dates from the given first to last day that no pause skips, earliest first, at most so many. A monthly plan's dates
     *  are its day in every month from the start's month, a day beyond the month's length falling on its last
     *  day, dates before the start left out. */
    private List<LocalDate> dates(LocalDate first, LocalDate last, int max) {
        List<LocalDate> out = new ArrayList<>();
        if (day != null) {
            for (YearMonth m = YearMonth.from(start); !m.atDay(1).isAfter(last) && out.size() < max;
                    m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && !d.isBefore(first) && !d.isAfter(last) && !skipped(d)) {
                    out.add(d);
                }
            }
            return out;
        }
        LocalDate d = start;
        if (d.isBefore(first)) {
            long steps = (ChronoUnit.DAYS.between(start, first) + every - 1) / every;
            d = start.plusDays(steps * every);
        }
        for (; !d.isAfter(last) && out.size() < max; d = d.plusDays(every)) {
            if (!skipped(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Keep that the plan has made its claim for the plan date. */
    public void generated(LocalDate date) {
        generated.add(date);
    }
}
