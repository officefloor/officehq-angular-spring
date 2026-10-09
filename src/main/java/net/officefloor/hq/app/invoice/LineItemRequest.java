package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Payload to add a line item to a draft invoice. */
public record LineItemRequest(
        @NotBlank @Size(max = 255) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal qty,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
}
