package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/**
 * A bulk settle settled its claims as the sheet's settle batch: the number of claims in it and the frozen
 * figures of those that went SETTLED (not PENDING), added up.
 */
public record BatchSettled(String sheetId, String user, int batch, int count, BigDecimal total) {
}
