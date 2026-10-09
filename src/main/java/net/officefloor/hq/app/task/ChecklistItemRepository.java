package net.officefloor.hq.app.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    /** The checklist items of all a project's tasks, in the order they were added. */
    List<ChecklistItem> findByTaskProjectIdOrderById(Long projectId);

    /** A task's checklist items, in the order they were added. */
    List<ChecklistItem> findByTaskIdOrderById(Long taskId);

    Optional<ChecklistItem> findByIdAndTaskIdAndTaskProjectId(Long id, Long taskId, Long projectId);
}
