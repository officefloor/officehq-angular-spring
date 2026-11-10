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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * A person's plan: an amount, a start date, every so many days and an end date. Its plan dates are
 * the start, then every that many days, up to the end; it keeps each date it has made a claim for.
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

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    @Column(name = "every_days", nullable = false)
    private int every;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "claim_date", nullable = false)
    private Set<LocalDate> made = new HashSet<>();

    protected Plan() {
    }

    public Plan(String sheetId, long personId, BigDecimal amount, LocalDate start, int every, LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
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

    public int getEvery() {
        return every;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** Whether the plan has made its claim for this date. */
    public boolean hasMade(LocalDate date) {
        return made.contains(date);
    }

    public void markMade(LocalDate date) {
        made.add(date);
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }
}
