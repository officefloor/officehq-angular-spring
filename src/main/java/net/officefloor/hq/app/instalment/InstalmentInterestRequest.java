package net.officefloor.hq.app.instalment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to set the interest charged for each day an instalment is paid late; zero charges none. */
public record InstalmentInterestRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal interestPerDay) {
}
