package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A person's plan: an amount on its start date and every so many days after, or monthly on a day of
 * the month (from the start's month, a day beyond the month's length falling on its last day, dates
 * before the start left out), up to its end date. It may name one of its sheet's descendants, on which its
 * claims are then generated for the person of the same name.
 */
@Entity
@Table(name = "claim_plan")
public class ClaimPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_on", nullable = false)
    private LocalDate start;

    /** Every how many days; none when the plan runs monthly. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) a monthly plan runs on; none when it runs every so many days. */
    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_on", nullable = false)
    private LocalDate end;

    /** The descendant sheet the plan's claims are generated on; none: the plan's own sheet. */
    @Column(name = "target_sheet")
    private String targetSheet;

    protected ClaimPlan() {}

    public ClaimPlan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end, String targetSheet) {
        this.targetSheet = targetSheet;
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.day = day;
        this.end = end;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public BigDecimal getAmount() { return amount; }

    public LocalDate getStart() { return start; }

    public Integer getEvery() { return every; }

    public Integer getDay() { return day; }

    public LocalDate getEnd() { return end; }

    public String getTargetSheet() { return targetSheet; }

    /** Change the amount the plan's claims are generated with from now on. */
    public void changeAmount(BigDecimal amount) { this.amount = amount; }

    /**
     * The plan's dates up to its end and the day given: the start then every so many days, or its day
     * in every month from the start's month (the month's last day when shorter), none before the start.
     */
    public List<LocalDate> datesUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        if (day != null) {
            for (YearMonth m = YearMonth.from(start); !m.atDay(1).isAfter(last); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && !d.isAfter(last)) dates.add(d);
            }
        } else {
            for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) dates.add(d);
        }
        return dates;
    }

    /** The plan's first so many dates after the day given, on or before its end, those skipped left out. */
    public List<LocalDate> datesAfter(LocalDate after, int count, Predicate<LocalDate> skipped) {
        List<LocalDate> next = new ArrayList<>();
        if (day != null) {
            YearMonth m = YearMonth.from(start.isAfter(after) ? start : after);
            for (; next.size() < count && !m.atDay(1).isAfter(end); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && d.isAfter(after) && !d.isAfter(end) && !skipped.test(d)) next.add(d);
            }
        } else {
            LocalDate d = start;
            if (after.isAfter(start)) d = start.plusDays((after.toEpochDay() - start.toEpochDay()) / every * every);
            for (; next.size() < count && !d.isAfter(end); d = d.plusDays(every)) {
                if (d.isAfter(after) && !skipped.test(d)) next.add(d);
            }
        }
        return next;
    }
}
