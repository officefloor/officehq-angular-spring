package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What a person's statements come to: how many were taken, and the last one's due-total (null when
 *  it was n/a) and day; both null when none was taken. */
public record StatementSummary(int count, BigDecimal lastDueTotal, LocalDate lastTakenOn) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null);

    /** The summary of one person's statements, given in the order they were taken. */
    public static StatementSummary of(List<Statement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        Statement last = statements.get(statements.size() - 1);
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getTakenOn());
    }
}
