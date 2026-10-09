package net.officefloor.hq.app.project;

import jakarta.validation.constraints.NotNull;

/** Payload to mark a project billable or non-billable. */
public record ProjectBillableRequest(@NotNull Boolean billable) {
}
