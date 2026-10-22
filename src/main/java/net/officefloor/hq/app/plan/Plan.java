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
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A plan on a person: an amount claimed every N days, or monthly on a day of the month, from its start
 * date up to its end date, both included, with the claims it has made so far by the plan date each was made for.
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

    @Column(name = "start_on", nullable = false)
    private LocalDate start;

    /** Every N days, or null for a monthly plan. */
    @Column(name = "every_days")
    private Integer every;

    /** The day of the month (1 to 31) of a monthly plan, or null for one every N days. */
    @Column(name = "day_of_month")
    private Integer day;

    @Column(name = "end_on", nullable = false)
    private LocalDate end;

    /** The claims the plan has made, by the plan date each was made for. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_claim", joinColumns = @JoinColumn(name = "plan_id"))
    @MapKeyColumn(name = "on_date")
    @Column(name = "claim_id")
    private Map<LocalDate, Long> made = new TreeMap<>();

    protected Plan() {
    }

    public Plan(String sheetId, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.day = day;
        this.end = end;
    }

    /**
     * The plan's dates, oldest first, up to its end and the given day: from its start every N days, or for a
     * monthly plan its day in every month from the start's month (the month's last day when the month is
     * shorter), leaving out those before the start.
     */
    public List<LocalDate> datesUpTo(LocalDate today) {
        LocalDate last = today == null || end.isBefore(today) ? end : today;
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

    /** The first count plan dates after the given day, oldest first, on or before the plan's end. */
    public List<LocalDate> nextDates(LocalDate today, int count) {
        return datesUpTo(null).stream().filter(d -> d.isAfter(today)).limit(count).toList();
    }

    /** Whether the plan has made a claim for the date. */
    public boolean hasMade(LocalDate date) {
        return made.containsKey(date);
    }

    /** Record the claim the plan made for the date. */
    public void made(LocalDate date, long claimId) {
        made.put(date, claimId);
    }

    /** The plan now belongs to another person, as when its person is merged into them. */
    public void moveTo(long personId) {
        this.personId = personId;
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

    /** Every N days, or null for a monthly plan. */
    public Integer getEvery() {
        return every;
    }

    /** The day of the month of a monthly plan, or null for one every N days. */
    public Integer getDay() {
        return day;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }
}
