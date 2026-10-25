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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A person's plan: a claim of {@code amount} on every plan date up to the end — the start then every
 * {@code every} days, or, for a monthly plan, {@code day} of every month from the start's month (the
 * month's last day when it is shorter), those before the start left out; with the plan dates it has made
 * its claim for, and the times it was paused: a plan date on or after a pause and before its resume is
 * skipped, not postponed; and the changes of its amount, oldest first: a change applies to the claims
 * generated after it, those already made keep theirs. */
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

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    @Column(name = "every_days")
    private Integer every;

    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_claim", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanClaim> claims = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    private Set<PlanPause> pauses = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_change", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    private List<PlanChange> changes = new ArrayList<>();

    protected Plan() {
    }

    /** A plan every {@code every} days (day null) or monthly on {@code day} (every null). */
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

    /** The day of the month (1 to 31) of a monthly plan; null for one every N days. */
    public Integer getDay() {
        return day;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return claims.size();
    }

    /** The plan dates up to the end and {@code today} that the plan has made no claim for yet, earliest first. */
    public List<LocalDate> datesDue(LocalDate today) {
        LocalDate last = end.isBefore(today) ? end : today;
        Set<LocalDate> made = claims.stream().map(PlanClaim::getDate).collect(Collectors.toSet());
        List<LocalDate> due = new ArrayList<>();
        for (LocalDate on : dates(last)) {
            if (!made.contains(on) && !skipped(on)) due.add(on);
        }
        return due;
    }

    /** The first {@code count} plan dates after {@code today} and on or before the end, earliest first. */
    public List<LocalDate> nextDates(LocalDate today, int count) {
        return dates(end).stream().filter(on -> on.isAfter(today) && !skipped(on)).limit(count).toList();
    }

    /** The changes of the plan's amount, oldest first. */
    public List<PlanChange> getChanges() {
        return List.copyOf(changes);
    }

    /** Change the plan's amount on {@code on} to {@code to}, for the claims generated from then on;
     * refused when it is not above zero. */
    public void changeAmount(LocalDate on, BigDecimal to) {
        if (to.signum() <= 0) throw new PlanAmountException();
        amount = to;
        changes.add(new PlanChange(on, to));
    }

    /** Whether the plan is paused now: paused and not yet resumed. */
    public boolean isPaused() {
        return pauses.stream().anyMatch(PlanPause::isOpen);
    }

    /** Pause the plan from {@code on}; refused when it is already paused. */
    public void pause(LocalDate on) {
        if (isPaused()) throw new PlanPauseException();
        pauses.add(new PlanPause(on, null));
    }

    /** Resume the plan on {@code on}; refused when it is not paused. */
    public void resume(LocalDate on) {
        PlanPause open = pauses.stream().filter(PlanPause::isOpen).findFirst().orElseThrow(PlanPauseException::new);
        pauses.remove(open);
        pauses.add(new PlanPause(open.getPausedOn(), on));
    }

    /** Whether the plan date {@code on} falls in a pause, so is skipped. */
    private boolean skipped(LocalDate on) {
        return pauses.stream().anyMatch(p -> p.skips(on));
    }

    /** The plan dates up to {@code last}, earliest first. */
    private List<LocalDate> dates(LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            for (LocalDate on = start; !on.isAfter(last); on = on.plusDays(every)) dates.add(on);
            return dates;
        }
        for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
            LocalDate on = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!on.isBefore(start) && !on.isAfter(last)) dates.add(on);
        }
        return dates;
    }

    /** Keep that the plan made its claim {@code claimId} for {@code date}. */
    public void made(LocalDate date, Long claimId) {
        claims.add(new PlanClaim(date, claimId));
    }
}
