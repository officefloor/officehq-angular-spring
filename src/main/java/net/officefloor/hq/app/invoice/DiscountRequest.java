package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Payload for a discount on a draft invoice: either a percentage taken off or a flat amount taken off
 * (omitted is none), never both. A percentage discount may be capped at the most it takes off (omitted is
 * no cap). Setting the discount with zero for both removes all discounts.
 */
public record DiscountRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discountPct,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal discountCap) {

    /** The flat amount taken off, zero when none is given. */
    public BigDecimal flatAmount() {
        return discountAmount == null ? BigDecimal.ZERO : discountAmount;
    }

    /** The discount is a percentage or a flat amount, not both at once. */
    @AssertTrue(message = "A discount is either a percentage or a flat amount, not both")
    public boolean isOneKind() {
        return discountPct == null || discountPct.signum() == 0 || flatAmount().signum() == 0;
    }

    /** Only a percentage discount can be capped. */
    @AssertTrue(message = "Only a percentage discount can be capped")
    public boolean isCapOnPercentage() {
        return discountCap == null || (discountPct != null && discountPct.signum() > 0);
    }
}
