package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** What a person shows of their statements: how many they have had, and the last one's due-total
 *  (null when it was n/a) and date, the one it was taken as of if any (both null with none), and a
 *  line for each statement still standing, by the date it shows (oldest first; taken order on a tie),
 *  so those a merge or a transfer brings over fall in among their own by date. */
public record StatementSummary(int count, BigDecimal lastDueTotal, LocalDate lastDate, List<Line> lines) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null, List.of());

    /** A statement line: the date the statement shows and its due-total (null when it was n/a). */
    public record Line(LocalDate date, BigDecimal dueTotal) {
    }

    /** The summary of one person's statements, oldest first. */
    public static StatementSummary of(List<PersonStatement> statements) {
        if (statements.isEmpty()) {
            return NONE;
        }
        PersonStatement last = statements.get(statements.size() - 1);
        List<Line> lines = statements.stream()
                .sorted(Comparator.comparing(PersonStatement::getStatedOn))
                .map(s -> new Line(s.getStatedOn(), s.getDueTotal()))
                .toList();
        return new StatementSummary(statements.size(), last.getDueTotal(), last.getStatedOn(), lines);
    }
}
