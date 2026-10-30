package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A plan was added to a person ({@code PLAN_ADDED}); {@code every} or, for a monthly plan, {@code day} is set, and {@code sheet} when it names a descendant sheet. */
public record PlanAdded(String sheetId, String user, String name, BigDecimal amount, LocalDate start,
        Integer every, Integer day, LocalDate end, String sheet) {
}
