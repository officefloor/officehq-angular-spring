package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;

/** Summary figures for the dashboard, with the clients who owe the most. */
public record DashboardResponse(long clients, long projects, BigDecimal outstanding, long overdue,
        List<TopClient> topClients) {

    /** A client and what they still owe. */
    public record TopClient(Long id, String name, BigDecimal outstanding) {
    }
}
