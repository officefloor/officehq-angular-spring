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

/** A person's plan: a DRAFT claim of its amount for each plan date, up to its end. The plan dates
 *  are its start and then every so many days; or, for a monthly plan, its day of the month in every
 *  month from the start's month (a day beyond the month's length falling on its last day), leaving
 *  out those before the start. */
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

    /** The plan's dates up to its end and the date given, in order. */
    public List<LocalDate> datesUpTo(LocalDate until) {
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
