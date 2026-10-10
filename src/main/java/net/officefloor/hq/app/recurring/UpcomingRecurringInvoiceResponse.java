package net.officefloor.hq.app.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.project.Project;

/** A recurring invoice that is coming up, with the project and client it bills and the currency it is in. */
public record UpcomingRecurringInvoiceResponse(Long id, Long projectId, String projectName, Long clientId,
        String clientName, String currency, BigDecimal amount, RecurringFrequency frequency, LocalDate nextDate) {

    static UpcomingRecurringInvoiceResponse from(RecurringInvoice recurring, Project project) {
        return new UpcomingRecurringInvoiceResponse(recurring.getId(), recurring.getProjectId(), project.getName(),
                project.getClient().getId(), project.getClient().getName(), project.getClient().getCurrency(),
                recurring.getAmount(), recurring.getFrequency(), recurring.getNextDate());
    }
}
