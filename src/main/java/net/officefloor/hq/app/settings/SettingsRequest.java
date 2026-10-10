package net.officefloor.hq.app.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Payload to change the app-wide settings: the sales tax percentage new invoices start with, and optionally when
 * revenue counts ("sent" or "paid"; left unchanged when not given).
 */
public record SettingsRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal defaultTaxPct,
        @Pattern(regexp = RecognitionBasis.SENT + "|" + RecognitionBasis.PAID) String revenueRecognitionBasis) {
}
