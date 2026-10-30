package net.officefloor.hq.app.statement;

import java.math.BigDecimal;

/** A statement was taken for a person ({@code STATEMENT}); the due-total is null when it was n/a. */
public record StatementTaken(String sheetId, String user, String name, BigDecimal dueTotal, int claims) {
}
