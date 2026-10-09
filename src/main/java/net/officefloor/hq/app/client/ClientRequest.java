package net.officefloor.hq.app.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to create a client, or to correct an existing client's name, email and phone number. */
public record ClientRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email(regexp = ClientRequest.EMAIL_PATTERN) @Size(max = 255) String email,
        @Size(max = 50) String phone) {

    /** Requires a dotted domain, which plain {@code @Email} does not (it accepts {@code a@b}). */
    public static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /** The phone number trimmed, or null when none was given. */
    public String trimmedPhone() {
        return phone == null || phone.isBlank() ? null : phone.trim();
    }
}
