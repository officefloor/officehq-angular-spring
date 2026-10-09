package net.officefloor.hq.app.credit;

import java.math.BigDecimal;

/**
 * The credit a client has to spend: deposits still held ({@code deposits}) plus the part of their credit notes not
 * taken up settling the invoice they were raised against ({@code creditNotes}); {@code total} is the two added up.
 */
public record ClientCreditResponse(BigDecimal deposits, BigDecimal creditNotes, BigDecimal total) {
}
