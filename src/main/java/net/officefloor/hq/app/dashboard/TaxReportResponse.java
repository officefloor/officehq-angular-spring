package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The sales tax charged on invoices issued on or between two dates, in the home currency, broken down by tax rate
 * (lowest rate first): for each rate, the taxable base it was charged on and the tax, with the totals over all rates.
 */
public record TaxReportResponse(LocalDate from, LocalDate to, String homeCurrency, long invoices,
        BigDecimal taxableBase, BigDecimal tax, List<RateTax> rates) {

    /** The sales tax charged at one rate (a percentage), in the home currency. */
    public record RateTax(BigDecimal rate, long invoices, BigDecimal taxableBase, BigDecimal tax) {
    }
}
