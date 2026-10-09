package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Payload to add, or change, a line item on a draft invoice. The unit price may be given to a fraction of a cent (up to four decimal places); the unit is optional; a line is taxable unless marked tax-exempt. */
public record LineItemRequest(
        @NotBlank @Size(max = 255) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal qty,
        @Size(max = 50) String unit,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 4) BigDecimal unitPrice,
        Boolean taxExempt) {

    /** The unit with surrounding blanks removed, or null when none was given. */
    String normalizedUnit() {
        return unit == null || unit.isBlank() ? null : unit.strip();
    }

    /** Whether the line is tax-free; a line not saying so is taxable. */
    boolean exempt() {
        return Boolean.TRUE.equals(taxExempt);
    }
}
