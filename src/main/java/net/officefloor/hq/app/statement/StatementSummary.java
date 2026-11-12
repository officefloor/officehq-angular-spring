package net.officefloor.hq.app.statement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How many statements a person has had, and the due-total and date of the last one (null with none), with one line
 * per statement, oldest first by the sheet's today it was made on: the date it was taken as of, then the due-total.
 */
public record StatementSummary(int count, String lastDueTotal, LocalDate lastDate, List<Statement> statements) {

    public static final StatementSummary NONE = new StatementSummary(0, null, null, List.of());

    StatementSummary then(Statement statement) {
        List<Statement> all = new ArrayList<>(statements);
        all.add(statement);
        return new StatementSummary(count + 1, statement.getDueTotal(), statement.getTakenOn(), all);
    }

    /**
     * One line per statement, oldest first by the sheet's today it was made on (one made before that was kept first,
     * statements of the same date in their order): the date it was taken as of, then the due-total.
     */
    public List<String> lines() {
        return statements.stream()
                .sorted(Comparator.comparing(Statement::getMadeOn, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(s -> s.getTakenOn() + " " + s.getDueTotal())
                .toList();
    }
}
