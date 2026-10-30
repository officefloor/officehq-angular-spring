package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A person's statements as shown: how many were taken, and the last one's due-total and date (null with none). */
public record Statements(int count, BigDecimal lastDueTotal, LocalDate lastDate) {

    public static final Statements NONE = new Statements(0, null, null);

    /** A person's statements, in the order taken. */
    public static Statements of(List<Statement> taken) {
        if (taken.isEmpty()) {
            return NONE;
        }
        Statement last = taken.get(taken.size() - 1);
        return new Statements(taken.size(), last.getDueTotal(), last.getTakenOn());
    }
}
