package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** The sheet's plans in the order they were added. */
    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    List<Plan> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    /** Moves the plans of one person to another. */
    @Modifying(flushAutomatically = true)
    @Query("update Plan p set p.personId = :into where p.sheetId = :sheetId and p.personId = :from")
    int movePlans(String sheetId, Long from, Long into);
}
