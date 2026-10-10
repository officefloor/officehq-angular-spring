package net.officefloor.hq.app.client;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload to create a client, or to correct an existing client's name, email, phone number, tax number, billing address, preferred language, account manager and billing contact,
 * whether their prices already include tax and whether they are tax exempt and whether they are a key account and their standard discount percentage (each left as it is when not given).
 */
public record ClientRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email(regexp = ClientRequest.EMAIL_PATTERN) @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 50) String taxNumber,
        @Size(max = 500) String billingAddress,
        @Size(max = 50) String language,
        @Size(max = 255) String accountManager,
        @Size(max = 255) String billingContact,
        Boolean taxInclusive,
        Boolean taxExempt,
        Boolean keyAccount,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal defaultDiscountPct) {

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

    /** The preferred language trimmed, or null when none was given. */
    public String trimmedLanguage() {
        return language == null || language.isBlank() ? null : language.trim();
    }

    /** The account manager trimmed, or null when none was given. */
    public String trimmedAccountManager() {
        return accountManager == null || accountManager.isBlank() ? null : accountManager.trim();
    }

    /** The billing contact trimmed, or null when none was given. */
    public String trimmedBillingContact() {
        return billingContact == null || billingContact.isBlank() ? null : billingContact.trim();
    }

    /** The billing address trimmed, or null when none was given. */
    public String trimmedBillingAddress() {
        return billingAddress == null || billingAddress.isBlank() ? null : billingAddress.trim();
    }
}
