package net.officefloor.hq.app.statement;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** A person's statements as their row shows them: how many were taken, the last one's due-total
 *  and date (null when none was taken), and a line for each, oldest first. */
public record StatementSummary(int count, String lastDueTotal, LocalDate lastDate, List<Line> lines) {

    /** One statement as its person's row lists it: its date and the due-total it recorded. */
    public record Line(LocalDate date, String dueTotal) {
    }

    public static final StatementSummary NONE = new StatementSummary(0, null, null, List.of());

    /** The summary of one person's statements, in their order (the last is their last statement). */
    public static StatementSummary of(List<Statement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        Statement last = statements.get(statements.size() - 1);
        // In the order they were taken (one taken as of a past day falls where it was taken); those a
        // merge brought over fall before the person's own.
        List<Line> lines = statements.stream()
                .sorted(Comparator.comparingLong(Statement::getOrder))
                .map(statement -> new Line(statement.getTakenOn(), statement.getDueTotal())).toList();
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getTakenOn(), lines);
    }
}
