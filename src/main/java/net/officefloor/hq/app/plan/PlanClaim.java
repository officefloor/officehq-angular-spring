package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A plan date a plan has made its claim for, and that claim. */
@Embeddable
public class PlanClaim {

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    protected PlanClaim() {
    }

    public PlanClaim(LocalDate date, Long claimId) {
        this.date = date;
        this.claimId = claimId;
    }

    public LocalDate getDate() {
        return date;
    }

    public Long getClaimId() {
        return claimId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanClaim other && Objects.equals(date, other.date) && Objects.equals(claimId, other.claimId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, claimId);
    }
}
