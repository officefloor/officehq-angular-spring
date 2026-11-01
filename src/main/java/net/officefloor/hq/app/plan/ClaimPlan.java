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

/** A person's plan: an amount on its start date and every so many days after, up to its end date. */
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

    @Column(name = "every_days", nullable = false)
    private int every;

    @Column(name = "end_on", nullable = false)
    private LocalDate end;

    protected ClaimPlan() {}

    public ClaimPlan(String sheetId, Long personId, BigDecimal amount, LocalDate start, int every, LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.end = end;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public BigDecimal getAmount() { return amount; }

    public LocalDate getStart() { return start; }

    public int getEvery() { return every; }

    public LocalDate getEnd() { return end; }

    /** The plan's dates, the start then every so many days, up to its end and the day given. */
    public List<LocalDate> datesUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) dates.add(d);
        return dates;
    }
}
