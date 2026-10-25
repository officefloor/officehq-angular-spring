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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's plan: a claim of its amount on every plan date, the start then every N days, up to its end. */
@Entity
@Table(name = "person_plan")
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

    /** The plan dates it has made a claim for. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_plan_date", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "plan_date", nullable = false)
    private Set<LocalDate> generated = new HashSet<>();

    protected Plan() {
    }

    Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, int every, LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.end = end;
    }

    /** The plan dates, oldest first, on or before both its end and the date. */
    List<LocalDate> datesUpTo(LocalDate upTo) {
        LocalDate last = end.isBefore(upTo) ? end : upTo;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) dates.add(d);
        return dates;
    }

    boolean hasGenerated(LocalDate date) {
        return generated.contains(date);
    }

    void markGenerated(LocalDate date) {
        generated.add(date);
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

    /** How many claims the plan has made so far. */
    public int getGeneratedCount() {
        return generated.size();
    }
}
