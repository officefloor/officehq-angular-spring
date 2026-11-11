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

/**
 * A person's plan: a claim of the amount on the start date, then every so many days, up to the end date; or, run
 * monthly, on a day of each month from the start's month (falling on the month's last day when it is shorter), the
 * dates before the start left out.
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

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    /** Every so many days, or null when the plan runs monthly. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) a monthly plan runs on, or null. */
    @Column(name = "month_day")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

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

    /** The plan dates on or before the given day, up to the end. */
    public List<LocalDate> datesUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        if (day != null) {
            for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
                LocalDate d = month.atDay(Math.min(day, month.lengthOfMonth()));
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

    /** The first so many plan dates after the given day, up to the end. */
    public List<LocalDate> datesAfter(LocalDate after, int limit) {
        List<LocalDate> dates = new ArrayList<>();
        if (day != null) {
            for (YearMonth month = YearMonth.from(start); dates.size() < limit && !month.atDay(1).isAfter(end);
                    month = month.plusMonths(1)) {
                LocalDate d = month.atDay(Math.min(day, month.lengthOfMonth()));
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
        for (; dates.size() < limit && !d.isAfter(end); d = d.plusDays(every)) {
            dates.add(d);
        }
        return dates;
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
}
