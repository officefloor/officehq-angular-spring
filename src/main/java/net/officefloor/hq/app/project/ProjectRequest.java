package net.officefloor.hq.app.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to create a project for a client; without a status the project starts active. The code is
 * a short reference of letters, digits and dashes, unique across projects. The description is an
 * optional short summary of the work. Without saying whether it is billable the project is billable.
 */
public record ProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = ProjectRequest.CODE_PATTERN) String code,
        @NotNull Long clientId,
        ProjectStatus status,
        @Size(max = ProjectRequest.DESCRIPTION_MAX) String description,
        Boolean billable) {

    /** The longest description a job may carry. */
    public static final int DESCRIPTION_MAX = 500;

    /** Up to 20 letters, digits or dashes, ignoring surrounding whitespace. */
    public static final String CODE_PATTERN = "^\\s*[A-Za-z0-9-]{1,20}\\s*$";
}
