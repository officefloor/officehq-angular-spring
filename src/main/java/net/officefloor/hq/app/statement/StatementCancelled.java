package net.officefloor.hq.app.statement;

import java.math.BigDecimal;

/** A person's last statement was cancelled ({@code STATEMENT_CANCELLED}); the due-total is null when it was n/a. */
public record StatementCancelled(String sheetId, String user, String name, BigDecimal dueTotal) {
}
