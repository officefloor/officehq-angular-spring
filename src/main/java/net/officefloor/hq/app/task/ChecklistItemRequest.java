package net.officefloor.hq.app.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to add a sub-item to a task's checklist. */
public record ChecklistItemRequest(@NotBlank @Size(max = 255) String text) {
}
