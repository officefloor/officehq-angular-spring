package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to set the percentage of a draft invoice held back as retention; zero holds none back. */
public record RetentionRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal retentionPct) {
}
