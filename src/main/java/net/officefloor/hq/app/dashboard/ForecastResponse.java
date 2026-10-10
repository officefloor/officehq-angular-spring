package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A simple cash-flow forecast: the money expected in from instalments still to be paid on invoices that have been
 * sent, earliest due first, each in its invoice's currency, and all of them together as one total in the home
 * currency (each converted at the exchange rate from its invoice's issue date).
 */
public record ForecastResponse(String homeCurrency, List<Entry> entries, BigDecimal total) {

    /** One instalment expected in: when it is due, how much, and which invoice, job and client it is from. */
    public record Entry(Long id, LocalDate date, BigDecimal amount, String currency, Long invoiceId, Long projectId,
            String projectName, String clientName) {
    }
}
