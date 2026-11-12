package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A plan date a plan has made its claim for. */
@Entity
@Table(name = "plan_date")
public class PlanDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate date;

    /** The claim made for the date (on the plan's sheet), or null when made before this was kept. */
    @Column(name = "claim_id")
    private Long claimId;

    protected PlanDate() {
    }

    public PlanDate(String sheetId, Long planId, LocalDate date, Long claimId) {
        this.sheetId = sheetId;
        this.planId = planId;
        this.date = date;
        this.claimId = claimId;
    }

    public Long getId() {
        return id;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getDate() {
        return date;
    }

    public Long getClaimId() {
        return claimId;
    }
}
