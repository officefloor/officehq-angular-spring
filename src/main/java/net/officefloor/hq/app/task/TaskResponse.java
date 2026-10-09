package net.officefloor.hq.app.task;

import java.time.LocalDate;

public record TaskResponse(Long id, Long projectId, String title, boolean done, LocalDate dueDate) {

    static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getTitle(), task.isDone(),
                task.getDueDate());
    }
}
