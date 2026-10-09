package net.officefloor.hq.app.client;

import jakarta.validation.constraints.NotBlank;

/** Payload to change the currency a client is billed in. */
public record ClientCurrencyRequest(@NotBlank String currency) {
}
