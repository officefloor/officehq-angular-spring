package net.officefloor.hq.app.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Payload to set the number of days a client has to pay an invoice (e.g. 30 for net 30); null removes the terms. */
public record ClientPaymentTermsRequest(@Min(0) @Max(365) Integer paymentTermsDays) {
}
