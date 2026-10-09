package net.officefloor.hq.app.refund;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Payload to pay part of a client's unused credit back to them, with an optional note of why. */
public record RefundRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Size(max = 500) String note) {
}
