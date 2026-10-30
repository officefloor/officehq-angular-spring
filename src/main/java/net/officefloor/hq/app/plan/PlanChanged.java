package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A plan's amount was changed ({@code PLAN_CHANGED}). */
public record PlanChanged(String sheetId, String user, String name, LocalDate start, BigDecimal amount) {
}
