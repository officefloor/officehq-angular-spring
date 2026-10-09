package net.officefloor.hq.app.task;

public record ChecklistItemResponse(Long id, Long taskId, String text, boolean done) {

    static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(item.getId(), item.getTask().getId(), item.getText(), item.isDone());
    }
}
