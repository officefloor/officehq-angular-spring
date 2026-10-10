package net.officefloor.hq.app.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringInvoiceResponse(Long id, Long projectId, BigDecimal amount, RecurringFrequency frequency,
        LocalDate nextDate, boolean due) {

    /** {@code due} is whether the next invoice has fallen due by the given day, so it can be raised now. */
    static RecurringInvoiceResponse from(RecurringInvoice recurring, LocalDate today) {
        return new RecurringInvoiceResponse(recurring.getId(), recurring.getProjectId(), recurring.getAmount(),
                recurring.getFrequency(), recurring.getNextDate(), !recurring.getNextDate().isAfter(today));
    }
}
