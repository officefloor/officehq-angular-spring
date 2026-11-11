package net.officefloor.hq.app.statement;

import java.time.LocalDate;

/** How many statements a person has had, and the due-total and date of the last one (null with none). */
public record StatementSummary(int count, String lastDueTotal, LocalDate lastDate) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null);

    StatementSummary then(Statement statement) {
        return new StatementSummary(count + 1, statement.getDueTotal(), statement.getTakenOn());
    }
}
