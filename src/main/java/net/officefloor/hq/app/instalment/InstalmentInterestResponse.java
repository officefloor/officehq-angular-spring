package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;

/** The interest charged for each day an instalment of an invoice is paid late. */
public record InstalmentInterestResponse(BigDecimal interestPerDay) {
}
