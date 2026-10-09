package net.officefloor.hq.app.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to create a client. */
public record ClientRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email(regexp = ClientRequest.EMAIL_PATTERN) @Size(max = 255) String email) {

    /** Requires a dotted domain, which plain {@code @Email} does not (it accepts {@code a@b}). */
    static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
}
