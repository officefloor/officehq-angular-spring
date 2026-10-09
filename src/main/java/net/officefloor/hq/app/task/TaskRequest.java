package net.officefloor.hq.app.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to add a task to a project. */
public record TaskRequest(@NotBlank @Size(max = 255) String title) {
}
