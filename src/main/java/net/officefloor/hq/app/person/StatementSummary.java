package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What a person shows of their statements: how many they have had, and the last one's due-total
 *  (null when it was n/a) and date, the one it was taken as of if any (both null with none). */
public record StatementSummary(int count, BigDecimal lastDueTotal, LocalDate lastDate) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null);

    /** The summary of one person's statements, oldest first. */
    public static StatementSummary of(List<PersonStatement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        PersonStatement last = statements.get(statements.size() - 1);
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getStatedOn());
    }
}
