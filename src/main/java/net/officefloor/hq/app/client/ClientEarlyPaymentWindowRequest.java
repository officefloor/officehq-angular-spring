package net.officefloor.hq.app.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Payload to set the early-payment window in a client's payment terms: the days after issue within which an invoice
 * must be paid to earn its early-payment discount; null removes it.
 */
public record ClientEarlyPaymentWindowRequest(@Min(0) @Max(365) Integer earlyPaymentWindowDays) {
}
