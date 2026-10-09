package net.officefloor.hq.app.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload to record one lump payment from a client, split across their invoices. With {@code useCredit} the client's
 * credit (held deposits, then unused credit notes) is used up toward the invoices before the money received; any of
 * the money received that the invoices do not need is kept as credit for the client.
 */
public record ClientPaymentRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull LocalDate date,
        @NotEmpty List<@Valid @NotNull Allocation> allocations,
        Boolean useCredit) {

    public boolean usesCredit() {
        return Boolean.TRUE.equals(useCredit);
    }

    /** The share of the lump paid against one invoice. */
    public record Allocation(
            @NotNull Long invoiceId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }
}
