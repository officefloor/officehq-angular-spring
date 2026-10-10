package net.officefloor.hq.app.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringInvoiceResponse(Long id, Long projectId, BigDecimal amount, RecurringFrequency frequency,
        LocalDate nextDate, RecurringStatus status, boolean due, int periodDays,
        boolean prorateFirst, BigDecimal proratedAmount) {

    /**
     * {@code proratedAmount} is what the schedule's pro-rated first invoice bills, or null when its next
     * invoice is for the full amount.
     * {@code due} is whether an active schedule's next invoice has fallen due by the given day, so it can be raised now. */
    static RecurringInvoiceResponse from(RecurringInvoice recurring, LocalDate today) {
        return new RecurringInvoiceResponse(recurring.getId(), recurring.getProjectId(), recurring.getAmount(),
                recurring.getFrequency(), recurring.getNextDate(), recurring.getStatus(),
                !recurring.isPaused() && !recurring.getNextDate().isAfter(today),
                recurring.getPeriodDays(), recurring.isProrateFirst(),
                recurring.isProrateFirst() ? recurring.nextAmount() : null);
    }
}
