package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** What a person's statements come to: how many were taken (those carried to them with a merge or a
 *  transfer too), and the last one's due-total (null when
 *  it was n/a) and date (the day it was taken as of); both null when none was taken; and every one of
 *  them, oldest first by its date (those of a day in the order they were taken). */
public record StatementSummary(int count, BigDecimal lastDueTotal, LocalDate lastTakenOn, List<Line> lines) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null, List.of());

    /** One statement: its date (the day it was taken as of) and its due-total (null when it was n/a). */
    public record Line(LocalDate date, BigDecimal dueTotal) {
    }

    /** The summary of one person's statements, given in the order they were taken. */
    public static StatementSummary of(List<Statement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        Statement last = last(statements);
        List<Line> lines = statements.stream()
                .sorted(Comparator.comparing(Statement::getStatedOn))
                .map(st -> new Line(st.getStatedOn(), st.getDueTotal()))
                .toList();
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getStatedOn(), lines);
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
