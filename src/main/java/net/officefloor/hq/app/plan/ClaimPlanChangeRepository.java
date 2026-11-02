package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPlanChangeRepository extends JpaRepository<ClaimPlanChange, Long> {

    List<ClaimPlanChange> findBySheetIdOrderByIdAsc(String sheetId);
}
