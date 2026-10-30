package net.officefloor.hq.app.plan;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanClaimRepository extends JpaRepository<PlanClaim, Long> {

    boolean existsByPlanIdAndOn(Long planId, LocalDate on);

    long countByPlanId(Long planId);

    List<PlanClaim> findByPlanIdOrderByIdAsc(Long planId);
}
