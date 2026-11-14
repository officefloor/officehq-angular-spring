package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A person's plan: a DRAFT claim of its amount (two decimals) for every plan date, its start and
 *  then every so many days, up to its end. */
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

    @Column(name = "every_days", nullable = false)
    private int every;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    /** The sheet's today when the plan was added. */
    @Column(name = "made_on", nullable = false, updatable = false)
    private LocalDate madeOn;

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, int every, LocalDate end,
            LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
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

    public int getEvery() {
        return every;
    }

    public LocalDate getEnd() {
        return end;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** The plan's dates up to its end and the day given, the earliest first. */
    public List<LocalDate> datesUpTo(LocalDate day) {
        LocalDate last = end.isBefore(day) ? end : day;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
            dates.add(d);
        }
        return dates;
    }
}
