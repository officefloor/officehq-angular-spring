package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A simple cash-flow forecast: the money expected in from instalments still to be paid on invoices that have been
 * sent, earliest due first, each in its invoice's currency, and all of them together as one total in the home
 * currency (each converted at the exchange rate from its invoice's issue date). Also what can be expected to be
 * collected in the next 30 days (see {@link Window}).
 */
public record ForecastResponse(String homeCurrency, List<Entry> entries, BigDecimal total, Window next30Days) {

    /**
     * What falls due on or between two dates, in the home currency: on a sent invoice paid by instalments, its unpaid
     * instalments due in the window; on any other sent invoice, what is left to pay on it when it is due in the window.
     */
    public record Window(LocalDate from, LocalDate to, BigDecimal total) {
    }

    /** One instalment expected in: when it is due, how much, and which invoice, job and client it is from. */
    public record Entry(Long id, LocalDate date, BigDecimal amount, String currency, Long invoiceId, Long projectId,
            String projectName, String clientName) {
    }
}
