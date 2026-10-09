package net.officefloor.hq.app.deposit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** Payload to put part of a client's held deposits toward their invoices, split across them. */
public record DepositApplicationRequest(@NotEmpty List<@Valid @NotNull Allocation> allocations) {

    /** The share of the deposits put toward one invoice. */
    public record Allocation(
            @NotNull Long invoiceId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }
}
