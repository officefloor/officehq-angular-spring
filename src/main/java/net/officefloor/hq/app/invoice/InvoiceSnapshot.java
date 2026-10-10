package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An invoice as it read when it was sent: the total it showed and the day it went out. */
public record InvoiceSnapshot(BigDecimal total, LocalDate date) {
}
