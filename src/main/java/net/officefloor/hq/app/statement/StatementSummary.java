package net.officefloor.hq.app.statement;

import java.time.LocalDate;
import java.util.List;

/** A person's statements as their row shows them: how many were taken, and the last one's
 *  due-total and date (null when none was taken). */
public record StatementSummary(int count, String lastDueTotal, LocalDate lastDate) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null);

    /** The summary of one person's statements, in the order they were taken. */
    public static StatementSummary of(List<Statement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        Statement last = statements.get(statements.size() - 1);
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getTakenOn());
    }
}
