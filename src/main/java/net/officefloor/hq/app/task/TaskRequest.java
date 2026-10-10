package net.officefloor.hq.app.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Payload to add a task to a project; the due date, assignee and priority are optional. */
public record TaskRequest(@NotBlank @Size(max = 255) String title, LocalDate dueDate,
        @Size(max = 255) String assignee,
        @Pattern(regexp = "Low|Medium|High") String priority) {
}
