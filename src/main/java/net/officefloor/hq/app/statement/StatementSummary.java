package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What a person's statements come to: how many were taken (those carried to them with a merge or a
 *  transfer too), and the last one's due-total (null when
 *  it was n/a) and date (the day it was taken as of); both null when none was taken. */
public record StatementSummary(int count, BigDecimal lastDueTotal, LocalDate lastTakenOn) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null);

    /** The summary of one person's statements, given in the order they were taken. */
    public static StatementSummary of(List<Statement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        Statement last = last(statements);
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getStatedOn());
    }

    /** A person's last statement, of theirs given in the order they were taken: the last of their
     *  own, or, having none, the last one carried to them; null when there are none. */
    public static Statement last(List<Statement> statements) {
        Statement last = null;
        for (Statement s : statements) {
            if (last == null || !s.isCarried() || last.isCarried()) {
                last = s;
            }
        }
        return last;
    }
}
