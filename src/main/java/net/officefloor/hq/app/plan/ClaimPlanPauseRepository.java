package net.officefloor.hq.app.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPlanPauseRepository extends JpaRepository<ClaimPlanPause, Long> {

    List<ClaimPlanPause> findBySheetId(String sheetId);

    List<ClaimPlanPause> findByPlanId(Long planId);

    Optional<ClaimPlanPause> findByPlanIdAndUntilIsNull(Long planId);
}
