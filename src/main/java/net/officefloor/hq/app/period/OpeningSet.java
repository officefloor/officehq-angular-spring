package net.officefloor.hq.app.period;

import java.math.BigDecimal;

/** An opening balance was added to a person's carried figure for a period ({@code OPENING_SET}). */
public record OpeningSet(String sheetId, String user, String name, String period, BigDecimal amount) {
}
