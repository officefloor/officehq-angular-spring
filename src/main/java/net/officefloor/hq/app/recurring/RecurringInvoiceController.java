package net.officefloor.hq.app.recurring;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recurring invoices across all projects. */
@RestController
@RequestMapping("/api/recurring-invoices")
public class RecurringInvoiceController {

    private final RecurringInvoiceService service;

    public RecurringInvoiceController(RecurringInvoiceService service) {
        this.service = service;
    }

    /** The recurring invoices coming up from today, soonest first. */
    @GetMapping("/upcoming")
    public List<UpcomingRecurringInvoiceResponse> upcoming() {
        return service.upcoming();
    }
}
