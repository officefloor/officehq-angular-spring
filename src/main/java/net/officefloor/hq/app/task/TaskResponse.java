package net.officefloor.hq.app.task;

import java.time.LocalDate;
import java.util.List;

public record TaskResponse(Long id, Long projectId, String title, boolean done, LocalDate dueDate,
        String assignee, String priority, List<ChecklistItemResponse> checklist) {

    static TaskResponse from(Task task, List<ChecklistItemResponse> checklist) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getTitle(), task.isDone(),
                task.getDueDate(), task.getAssignee(), task.getPriority(), checklist);
    }
}
