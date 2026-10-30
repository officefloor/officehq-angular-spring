package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A plan was added to a person ({@code PLAN_ADDED}). */
public record PlanAdded(String sheetId, String user, String name, BigDecimal amount, LocalDate start, int every,
        LocalDate end) {
}
