package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    /** Remove the person's plans, with their dates, pauses and changes. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM plan WHERE person_id = :personId", nativeQuery = true)
    void deleteByPersonId(Long personId);
}
