package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A plan date the plan has made its claim for, and that claim (null when made before it was kept). */
@Embeddable
public class PlanDate {

    @Column(name = "claim_date", nullable = false)
    private LocalDate claimDate;

    @Column(name = "claim_id")
    private Long claimId;

    protected PlanDate() {
    }

    PlanDate(LocalDate claimDate, Long claimId) {
        this.claimDate = claimDate;
        this.claimId = claimId;
    }

    public LocalDate getClaimDate() {
        return claimDate;
    }

    public Long getClaimId() {
        return claimId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanDate other && claimDate.equals(other.claimDate)
                && Objects.equals(claimId, other.claimId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(claimDate, claimId);
    }
}
