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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A person's plan: a claim of {@code amount} on every plan date, the start then every {@code every} days,
 * up to the end; with the plan dates it has made its claim for. */
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

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate start;

    @Column(name = "every_days", nullable = false)
    private int every;

    @Column(name = "end_date", nullable = false)
    private LocalDate end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_claim", joinColumns = @JoinColumn(name = "plan_id"))
    private List<PlanClaim> claims = new ArrayList<>();

    protected Plan() {
    }

    public Plan(String sheetId, Long personId, BigDecimal amount, LocalDate start, int every, LocalDate end) {
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

    /** How many claims the plan has made so far. */
    public int getGenerated() {
        return claims.size();
    }

    /** The plan dates up to the end and {@code today} that the plan has made no claim for yet, earliest first. */
    public List<LocalDate> datesDue(LocalDate today) {
        LocalDate last = end.isBefore(today) ? end : today;
        Set<LocalDate> made = claims.stream().map(PlanClaim::getDate).collect(Collectors.toSet());
        List<LocalDate> due = new ArrayList<>();
        for (LocalDate on = start; !on.isAfter(last); on = on.plusDays(every)) {
            if (!made.contains(on)) due.add(on);
        }
        return due;
    }

    /** Keep that the plan made its claim {@code claimId} for {@code date}. */
    public void made(LocalDate date, Long claimId) {
        claims.add(new PlanClaim(date, claimId));
    }
}
