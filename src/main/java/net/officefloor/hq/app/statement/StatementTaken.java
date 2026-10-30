package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A statement was taken for a person ({@code STATEMENT}); the due-total is null when it was n/a, the as-of
 * date null when none was given.
 */
public record StatementTaken(String sheetId, String user, String name, BigDecimal dueTotal, int claims,
        LocalDate asOf) {
}
