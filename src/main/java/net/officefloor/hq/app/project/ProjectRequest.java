package net.officefloor.hq.app.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Payload to create a project for a client. */
public record ProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Long clientId) {
}
