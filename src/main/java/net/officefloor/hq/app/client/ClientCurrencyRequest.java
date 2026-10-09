package net.officefloor.hq.app.client;

import jakarta.validation.constraints.NotNull;

/** Payload to change the currency a client is billed in. */
public record ClientCurrencyRequest(@NotNull Currency currency) {
}
