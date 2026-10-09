package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;

/** Summary figures for the dashboard. */
public record DashboardResponse(long clients, long projects, BigDecimal outstanding, long overdue) {
}
