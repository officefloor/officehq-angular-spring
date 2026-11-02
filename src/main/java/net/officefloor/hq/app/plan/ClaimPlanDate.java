package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A date a plan has made its claim for: it makes none for it again. */
@Entity
@Table(name = "claim_plan_date")
public class ClaimPlanDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "claim_id")
    private Long claimId;

    protected ClaimPlanDate() {}

    public ClaimPlanDate(String sheetId, Long planId, LocalDate madeOn, Long claimId) {
        this.sheetId = sheetId;
        this.planId = planId;
        this.madeOn = madeOn;
        this.claimId = claimId;
    }

    public Long getPlanId() { return planId; }

    public LocalDate getMadeOn() { return madeOn; }

    /** The claim made for the date (null when not known). */
    public Long getClaimId() { return claimId; }
}
