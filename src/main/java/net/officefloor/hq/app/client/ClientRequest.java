package net.officefloor.hq.app.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to create a client, or to correct an existing client's name, email, phone number, tax number and billing address,
 * and whether their prices already include tax (left as it is when not given).
 */
public record ClientRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email(regexp = ClientRequest.EMAIL_PATTERN) @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 50) String taxNumber,
        @Size(max = 500) String billingAddress,
        Boolean taxInclusive) {

    /** Requires a dotted domain, which plain {@code @Email} does not (it accepts {@code a@b}). */
    public static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /** The phone number trimmed, or null when none was given. */
    public String trimmedPhone() {
        return phone == null || phone.isBlank() ? null : phone.trim();
    }

    /** The tax number trimmed, or null when the client is not tax registered. */
    public String trimmedTaxNumber() {
        return taxNumber == null || taxNumber.isBlank() ? null : taxNumber.trim();
    }

    /** The billing address trimmed, or null when none was given. */
    public String trimmedBillingAddress() {
        return billingAddress == null || billingAddress.isBlank() ? null : billingAddress.trim();
    }
}
