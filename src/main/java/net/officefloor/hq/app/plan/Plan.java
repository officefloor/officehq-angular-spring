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

/** A person's plan: a draft claim of its amount on its start date and every so many days after, or
 *  on a day of every month from the start's month (a day beyond the month's length falling on its
 *  last day, dates before the start left out), up to its end date. */
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

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** The days it repeats every; null for a monthly plan. */
    @Column(name = "every_days")
    private Integer everyDays;

    /** The day of the month a monthly plan falls on; null for one that repeats every so many days. */
    @Column(name = "month_day")
    private Integer monthDay;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** The descendant sheet whose person of the same name its claims are made for; null for its own sheet. */
    @Column(name = "target_sheet_id")
    private String targetSheetId;

    /** The plan dates it has made its claim for. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "on_date", nullable = false)
    private Set<LocalDate> made = new HashSet<>();

    /** The claims it has generated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_claim", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claims = new HashSet<>();

    /** The times it was paused; a plan date in one is skipped. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_pause", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanPause> pauses = new ArrayList<>();

    /** The changes of its amount, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_change", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderColumn(name = "position")
    private List<PlanChange> changes = new ArrayList<>();

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate startDate, Integer everyDays,
            Integer monthDay, LocalDate endDate, String targetSheetId) {
        this.sheetId = sheetId;
        this.targetSheetId = targetSheetId;
        this.personId = personId;
        this.amount = amount;
        this.startDate = startDate;
        this.everyDays = everyDays;
        this.monthDay = monthDay;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /** The descendant sheet its claims are made on; null for its own sheet. */
    public String getTargetSheetId() {
        return targetSheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Make the plan another person's (the one it was for was merged into them). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** Change the amount the plan's claims take from now on, noting the change on the day given. */
    public void changeAmount(LocalDate on, BigDecimal amount) {
        this.amount = amount;
        changes.add(new PlanChange(on, amount));
    }

    /** The changes of its amount, oldest first. */
    public List<PlanChange> getChanges() {
        return List.copyOf(changes);
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public Integer getEveryDays() {
        return everyDays;
    }

    public Integer getMonthDay() {
        return monthDay;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }

    /** The plan dates up to its end and the day given, oldest first, that it has made no claim for
     *  and that no pause skips. */
    public List<LocalDate> dueUpTo(LocalDate today) {
        LocalDate last = endDate.isBefore(today) ? endDate : today;
        List<LocalDate> due = new ArrayList<>();
        if (monthDay != null) {
            for (YearMonth m = YearMonth.from(startDate); !m.atDay(1).isAfter(last); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(monthDay, m.lengthOfMonth()));
                if (!d.isBefore(startDate) && !d.isAfter(last) && !made.contains(d) && !skipped(d)) {
                    due.add(d);
                }
            }
            return due;
        }
        for (LocalDate d = startDate; !d.isAfter(last); d = d.plusDays(everyDays)) {
            if (!made.contains(d) && !skipped(d)) {
                due.add(d);
            }
        }
        return due;
    }

    /** The first plan dates after the day given and on or before its end, oldest first, at most as
     *  many as asked for, leaving out those skipped by a pause; none while it is paused. */
    public List<LocalDate> nextAfter(LocalDate today, int count) {
        List<LocalDate> next = new ArrayList<>();
        if (isPaused()) {
            return next;
        }
        if (monthDay != null) {
            for (YearMonth m = YearMonth.from(startDate); next.size() < count && !m.atDay(1).isAfter(endDate);
                    m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(monthDay, m.lengthOfMonth()));
                if (!d.isBefore(startDate) && d.isAfter(today) && !d.isAfter(endDate) && !skipped(d)) {
                    next.add(d);
                }
            }
            return next;
        }
        LocalDate d = startDate;
        if (d.isBefore(today)) {
            d = d.plusDays((today.toEpochDay() - d.toEpochDay()) / everyDays * everyDays);
        }
        for (; next.size() < count && !d.isAfter(endDate); d = d.plusDays(everyDays)) {
            if (d.isAfter(today) && !skipped(d)) {
                next.add(d);
            }
        }
        return next;
    }

    /** Note that the plan has made its claim for the date. */
    public void markMade(LocalDate date) {
        made.add(date);
    }

    /** Note that the plan has made its claim for the date, the claim given. */
    public void markMade(LocalDate date, Long claimId) {
        made.add(date);
        claims.add(claimId);
    }

    /** The ids of the claims it has generated, oldest first. */
    public List<Long> getClaimIds() {
        return claims.stream().sorted().toList();
    }

    /** Whether the plan is paused and not yet resumed. */
    public boolean isPaused() {
        return pauses.stream().anyMatch(PlanPause::isOpen);
    }

    /** Pause the plan from the day given. */
    public void pause(LocalDate on) {
        pauses.add(new PlanPause(on));
    }

    /** Resume the paused plan on the day given. */
    public void resume(LocalDate on) {
        pauses.stream().filter(PlanPause::isOpen).forEach(p -> p.resume(on));
    }

    /** Whether a pause skips the date. */
    private boolean skipped(LocalDate date) {
        return pauses.stream().anyMatch(p -> p.covers(date));
    }
}
