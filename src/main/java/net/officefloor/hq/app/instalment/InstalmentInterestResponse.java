package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;

/**
 * The interest charged for each day an instalment of an invoice is paid late, and the total interest
 * that has built up across the invoice's late instalments.
 */
public record InstalmentInterestResponse(BigDecimal interestPerDay, BigDecimal accrued) {
}
