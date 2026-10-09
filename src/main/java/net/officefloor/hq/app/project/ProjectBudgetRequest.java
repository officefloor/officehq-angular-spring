package net.officefloor.hq.app.project;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** Payload to set a project's budget; without a budget the project's budget is cleared. */
public record ProjectBudgetRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal budget) {
}
