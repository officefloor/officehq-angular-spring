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

/**
 * A person's plan: an amount on its start date and every so many days after, or monthly on a day of
 * the month (from the start's month, a day beyond the month's length falling on its last day, dates
 * before the start left out), up to its end date.
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

    protected ClaimPlan() {}

    public ClaimPlan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end) {
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
}
