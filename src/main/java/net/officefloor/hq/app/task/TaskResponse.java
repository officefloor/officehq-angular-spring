package net.officefloor.hq.app.task;

public record TaskResponse(Long id, Long projectId, String title, boolean done) {

    static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getTitle(), task.isDone());
    }
}
