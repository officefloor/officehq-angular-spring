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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A person's plan: a draft claim of its amount on each plan date, from the start every so many days to the end, or
 * monthly on a day of the month. The plan dates a claim has been made on are kept, so each is made once.
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

    /** The interval in days; null for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) of a monthly plan; null for a plan every so many days. */
    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_claim", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "on_date", nullable = false)
    private Set<LocalDate> made = new HashSet<>();

    /** The plan's pauses, in the order they were made; the last one is open while the plan is paused. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanPause> pauses = new ArrayList<>();

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
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

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
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

    /**
     * The plan's dates from the start up to the last date: every so many days from the start, or the day of the month
     * in every month from the start's month (on the month's last day when it is shorter), those before the start left
     * out.
     */
    public List<LocalDate> datesUpTo(LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            for (LocalDate on = start; !on.isAfter(last); on = on.plusDays(every)) {
                dates.add(on);
            }
            return dates;
        }
        for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
            LocalDate on = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!on.isBefore(start) && !on.isAfter(last)) {
                dates.add(on);
            }
        }
        return dates;
    }

    /**
     * The first plan dates after the day and on or before the end that no pause skips, at most so many of them; none
     * while the plan is paused.
     */
    public List<LocalDate> nextDates(LocalDate after, int count) {
        if (isPaused()) {
            return List.of();
        }
        return datesUpTo(end).stream().filter(on -> on.isAfter(after) && !isSkipped(on)).limit(count).toList();
    }

    /** Whether the plan is paused and not resumed yet. */
    public boolean isPaused() {
        return pauses.stream().anyMatch(PlanPause::isOpen);
    }

    /** Whether a pause skips the plan date: it is on or after the day paused and before the day resumed. */
    public boolean isSkipped(LocalDate date) {
        return pauses.stream().anyMatch(p -> p.skips(date));
    }

    /** Pauses the plan from the day. */
    public void pause(LocalDate on) {
        pauses.add(new PlanPause(on));
    }

    /** Resumes the paused plan from the day. */
    public void resume(LocalDate on) {
        pauses.stream().filter(PlanPause::isOpen).forEach(p -> p.resume(on));
    }

    public LocalDate getEnd() {
        return end;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }

    /** Whether a claim has been made for the plan date. */
    public boolean hasMade(LocalDate date) {
        return made.contains(date);
    }

    public void markMade(LocalDate date) {
        made.add(date);
    }
}
