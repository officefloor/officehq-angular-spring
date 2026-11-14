package net.officefloor.hq.app.plan;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanClaimRepository extends JpaRepository<PlanClaim, Long> {

    /** The claims the plans have made, in the order they were made. */
    List<PlanClaim> findByPlanIdInOrderByIdAsc(Collection<Long> planIds);
}
