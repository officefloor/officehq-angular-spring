package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How old a client's debt is as at today, in the client's currency: what is left to pay on their sent invoices split by
 * how many days past due each one is — current (not yet due, or up to 30 days overdue), 31 to 60 days, and more than
 * 60 days.
 */
public record ClientAgingResponse(Long clientId, String currency, LocalDate asOf, BigDecimal current,
        BigDecimal days30To60, BigDecimal days60Plus) {
}
