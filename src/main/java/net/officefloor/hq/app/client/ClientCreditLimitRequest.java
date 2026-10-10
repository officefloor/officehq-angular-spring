package net.officefloor.hq.app.client;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** Payload to set the most a client may owe, in their currency; null removes the limit. */
public record ClientCreditLimitRequest(@DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal creditLimit) {
}
