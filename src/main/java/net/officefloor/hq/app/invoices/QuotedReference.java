package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;

/**
 * A reference quoted with a payment (RF reference without spaces), with the amount of the payment it
 * gets when one was stated after an equals sign (INV-0000018=50); null when none was.
 */
public record QuotedReference(String reference, BigDecimal amount) {
}
