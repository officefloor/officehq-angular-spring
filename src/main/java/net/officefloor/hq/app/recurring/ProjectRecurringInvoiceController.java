package net.officefloor.hq.app.recurring;

import jakarta.validation.Valid;
import java.util.List;
import net.officefloor.hq.app.invoice.InvoiceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invoices on a project that repeat on a schedule for a fixed amount. */
@RestController
@RequestMapping("/api/projects/{projectId}/recurring-invoices")
public class ProjectRecurringInvoiceController {

    private final RecurringInvoiceService service;

    public ProjectRecurringInvoiceController(RecurringInvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecurringInvoiceResponse> list(@PathVariable Long projectId) {
        return service.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringInvoiceResponse create(@PathVariable Long projectId,
            @Valid @RequestBody RecurringInvoiceRequest request) {
        return service.create(projectId, request);
    }

    /** Raises the invoice the recurring schedule has fallen due for, as a draft to review before sending. */
    @PostMapping("/{recurringId}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse generate(@PathVariable Long projectId, @PathVariable Long recurringId) {
        return service.generate(projectId, recurringId);
    }
}
