package net.officefloor.hq.app.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.client.ClientRequest;

/** Payload to add a contact to a client. */
public record ContactRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email(regexp = ClientRequest.EMAIL_PATTERN) @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String role) {
}
