package net.officefloor.hq.app.project;

import jakarta.validation.constraints.NotNull;

/** Payload to mark a project active, on hold or finished. */
public record ProjectStatusRequest(@NotNull ProjectStatus status) {
}
