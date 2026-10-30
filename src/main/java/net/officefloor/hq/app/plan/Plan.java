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
 * A person's plan: a claim of the amount on the start date and every N days after it, or monthly on a day of the
 * month from the start's month (a day beyond the month's length on its last day, dates before the start left out), up
 * to the end date. Exactly one of {@code every} and {@code day} is set. A plan may name one of its sheet's
 * descendants, its claims then made for the person of the same name on that sheet.
 */
@Entity
@Table(name = "sheet_plan")
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

    @Column(name = "every_days")
    private Integer every;

    @Column(name = "month_day")
    private Integer day;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @Column(name = "target_sheet_id")
    private String targetSheetId;

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every, Integer day,
            LocalDate end, String targetSheetId) {
        this.targetSheetId = targetSheetId;
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

    /** Change the amount the plan's claims are made for from now on. */
    public void changeAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getStart() {
        return start;
    }

    /** Every how many days, or null for a monthly plan. */
    public Integer getEvery() {
        return every;
    }

    /** The day of the month a monthly plan runs on, or null for a plan every N days. */
    public Integer getDay() {
        return day;
    }

    /** The plan's dates from its start up to and including the last date, in order. */
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

    public LocalDate getEnd() {
        return end;
    }

    /** The descendant sheet the plan's claims are made on, or null for the plan's own sheet. */
    public String getTargetSheetId() {
        return targetSheetId;
    }
}
