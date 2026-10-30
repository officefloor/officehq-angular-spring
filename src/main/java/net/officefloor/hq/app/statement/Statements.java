package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * A person's statements as shown: how many were taken, the last one's due-total and date (null with none),
 * and every one still standing, oldest first by date (in the order taken on the same date).
 */
public record Statements(int count, BigDecimal lastDueTotal, LocalDate lastDate, List<Line> lines) {

    public static final Statements NONE = new Statements(0, null, null, List.of());

    /** A statement still standing: its date and its due-total (null when it was n/a). */
    public record Line(LocalDate date, BigDecimal dueTotal) {
    }

    /** A person's statements, in the order taken. */
    public static Statements of(List<Statement> taken) {
        if (taken.isEmpty()) {
            return NONE;
        }
        Statement last = taken.get(taken.size() - 1);
        List<Line> lines = taken.stream()
                .sorted(Comparator.comparing(Statement::getTakenOn).thenComparing(Statement::getId))
                .map(s -> new Line(s.getTakenOn(), s.getDueTotal())).toList();
        return new Statements(taken.size(), last.getDueTotal(), last.getTakenOn(), lines);
    }
}
