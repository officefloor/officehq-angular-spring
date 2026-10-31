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

/** A person's plan on a sheet: an amount claimed on its start date and every so many days after, or instead monthly
 *  on a day of the month from the start's month, up to its end date. */
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

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    /** Days between plan dates; empty for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) a monthly plan falls on; empty for a plan that runs every so many days. */
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

    /** The plan's dates from its start up to and including {@code last}, in order. A monthly plan falls on its day
     *  in every month from the start's month, on the month's last day when the day is beyond its length, leaving
     *  out dates before the start. */
    public List<LocalDate> datesUpTo(LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        if (day == null) {
            for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
                dates.add(d);
            }
            return dates;
        }
        for (YearMonth m = YearMonth.from(start); !m.atDay(1).isAfter(last); m = m.plusMonths(1)) {
            LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
            if (!d.isBefore(start) && !d.isAfter(last)) {
                dates.add(d);
            }
        }
        return dates;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** Move the plan to another person, as merging its person into them does. */
    public void reassign(Long personId) {
        this.personId = personId;
    }
}
