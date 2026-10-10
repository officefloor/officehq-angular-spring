package net.officefloor.hq.app.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringInvoiceResponse(Long id, Long projectId, BigDecimal amount, RecurringFrequency frequency,
        LocalDate nextDate) {

    static RecurringInvoiceResponse from(RecurringInvoice recurring) {
        return new RecurringInvoiceResponse(recurring.getId(), recurring.getProjectId(), recurring.getAmount(),
                recurring.getFrequency(), recurring.getNextDate());
    }
}
