package net.officefloor.hq.app.project;

import java.math.BigDecimal;

/**
 * A project's budget, how much has been invoiced against it (the totals before tax of every invoice
 * sent, whether paid or not; drafts are not yet invoiced) and what is left of it. Budget and remaining are null when no
 * budget is set.
 */
public record ProjectBudgetResponse(BigDecimal budget, BigDecimal invoiced, BigDecimal remaining) {

    static ProjectBudgetResponse of(BigDecimal budget, BigDecimal invoiced) {
        return new ProjectBudgetResponse(budget, invoiced, budget == null ? null : budget.subtract(invoiced));
    }
}
