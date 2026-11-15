package net.officefloor.hq.app.plan;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlanClaimRepository extends JpaRepository<PlanClaim, PlanClaim.Key> {

    boolean existsByPlanIdAndOnDate(long planId, LocalDate onDate);

    /** How many claims each of the sheet's plans has made: rows of plan id and count. */
    @Query("select pc.planId, count(pc) from PlanClaim pc, Plan p where p.id = pc.planId and p.sheetId = ?1 group by pc.planId")
    List<Object[]> countsBySheet(String sheetId);
}
