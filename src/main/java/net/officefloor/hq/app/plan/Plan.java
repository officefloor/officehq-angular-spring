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

/** A person's plan: a DRAFT claim of its amount on every plan date, the start then every so many days up
 *  to its end, and the plan dates it has made a claim for so far. */
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

    @Column(name = "every_days", nullable = false)
    private int every;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_generated", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "on_date")
    private Set<LocalDate> generated = new HashSet<>();

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

    public long getPersonId() {
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

    /** The plan dates up to its end and the given day that it has made no claim for yet, earliest first. */
    public List<LocalDate> datesDue(LocalDate today) {
        LocalDate last = end.isBefore(today) ? end : today;
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
            if (!generated.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Keep that the plan has made its claim for the plan date. */
    public void generated(LocalDate date) {
        generated.add(date);
    }
}
