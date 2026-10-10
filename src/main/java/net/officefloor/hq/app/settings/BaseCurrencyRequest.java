package net.officefloor.hq.app.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to choose the currency the dashboard's totals are shown in. */
public record BaseCurrencyRequest(@NotBlank @Size(min = 3, max = 3) String baseCurrency) {
}
