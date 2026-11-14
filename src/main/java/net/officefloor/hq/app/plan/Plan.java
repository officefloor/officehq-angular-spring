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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** A person's plan: a DRAFT claim of its amount (two decimals) for every plan date up to its end. The
 *  plan dates are its start and then every so many days, or, for a monthly plan, its day of the month
 *  in every month from the start's month (a day beyond the month's length on its last day), those
 *  before the start left out. */
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

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    /** Every so many days; null for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) a monthly plan runs on; null for one that runs every so many days. */
    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    /** The sheet's today when the plan was added. */
    @Column(name = "made_on", nullable = false, updatable = false)
    private LocalDate madeOn;

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.day = day;
        this.end = end;
        this.madeOn = madeOn;
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

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** The plan's dates up to its end and the day given, the earliest first. */
    public List<LocalDate> datesUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        if (day != null) {
            for (YearMonth m = YearMonth.from(start); !m.atDay(1).isAfter(last); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && !d.isAfter(last)) {
                    dates.add(d);
                }
            }
            return dates;
        }
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
            dates.add(d);
        }
        return dates;
    }

    /** The first so many plan dates after the day given and on or before its end, the earliest first. */
    public List<LocalDate> datesAfter(LocalDate after, int count) {
        List<LocalDate> dates = new ArrayList<>();
        if (day != null) {
            YearMonth m = YearMonth.from(after.isBefore(start) ? start : after);
            for (; dates.size() < count && !m.atDay(1).isAfter(end); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (!d.isBefore(start) && d.isAfter(after) && !d.isAfter(end)) {
                    dates.add(d);
                }
            }
            return dates;
        }
        LocalDate d = start;
        if (!after.isBefore(start)) {
            d = start.plusDays((ChronoUnit.DAYS.between(start, after) / every + 1) * every);
        }
        for (; dates.size() < count && !d.isAfter(end); d = d.plusDays(every)) {
            dates.add(d);
        }
        return dates;
    }
}
