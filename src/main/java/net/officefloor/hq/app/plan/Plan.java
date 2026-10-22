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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A plan on a person: an amount claimed every N days from its start date up to its end date, both
 * included, with the claims it has made so far by the plan date each was made for.
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

    @Column(name = "every_days", nullable = false)
    private int every;

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

    public Plan(String sheetId, long personId, BigDecimal amount, LocalDate start, int every, LocalDate end) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.start = start;
        this.every = every;
        this.end = end;
    }

    /** The plan's dates, oldest first, from its start every N days up to its end and the given day. */
    public List<LocalDate> datesUpTo(LocalDate today) {
        LocalDate last = today == null || end.isBefore(today) ? end : today;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(every)) {
            dates.add(d);
        }
        return dates;
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

    public int getEvery() {
        return every;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return made.size();
    }
}
