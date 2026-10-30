package net.officefloor.hq.app.plan;

import java.time.LocalDate;

/** A plan was paused ({@code PLAN_PAUSED}) or, when {@code resumed}, resumed ({@code PLAN_RESUMED}). */
public record PlanPaused(String sheetId, String user, String name, LocalDate start, boolean resumed) {
}
